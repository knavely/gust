package gust.linalg.cuda


import breeze.linalg.operators._
import breeze.linalg._
import breeze.linalg.support.{CanCollapseAxis, CanTranspose, CanSlice2}
import org.bridj.Pointer
import scala.reflect.ClassTag

import jcuda.jcublas.{cublasOperation, cublasHandle, JCublas2}
import gust.util.cuda
import jcuda.runtime.{cudaMemcpyKind, cudaStream_t, JCuda}
import jcuda.driver.CUstream
import cuda._
import jcuda.jcurand.{curandRngType, curandGenerator}
import breeze.math.Ring
import breeze.numerics._
import breeze.generic.UFunc

/**
 * TODO
 *
 * @author dlwh
 **/
class CuMatrix[V](val rows: Int,
                  val cols: Int,
                  val data: Pointer[V],
                  val offset: Int,
                  val majorStride: Int,
                  val isTranspose: Boolean = false)(implicit val blas: cublasHandle) extends NumericOps[CuMatrix[V]] {
  /** Creates a matrix with the specified data array, rows, and columns. Data must be column major */
  def this(rows: Int, cols: Int, data: Pointer[V], offset: Int = 0)(implicit blas: cublasHandle) = this(rows, cols, data, offset, rows)
  /** Creates a matrix with the specified data array, rows, and columns. */
  def this(rows: Int, cols: Int)(implicit blas: cublasHandle, ct: ClassTag[V]) = this(rows, cols, cuda.allocate[V](rows * cols))

  def size = rows * cols

  /** Calculates the index into the data array for row and column */
  final def linearIndex(row: Int, col: Int): Int = {
    if(isTranspose)
      offset + col + row * majorStride
    else
      offset + row + col * majorStride
  }

  def repr = this

  /*
  override def equals(p1: Any) = p1 match {
    case x: CuMatrix[_] =>

      // todo: make this faster in obvious cases
      rows == x.rows && cols == x.cols && (valuesIterator sameElements x.valuesIterator )

    case _ => false
  }
  */

  def majorSize = if(isTranspose) rows else cols

  def activeSize = size

  def footprint = majorSize * majorStride

  def isActive(i: Int) = true
  def allVisitableIndicesActive = true

  def elemSize = data.getIO.getTargetSize
  def offsetPointer = data.toCuPointer.withByteOffset(elemSize * offset)

  def writeFromDense(b: DenseMatrix[V]): Int = {
    require(b.rows == this.rows, "Matrices must have same number of rows")
    require(b.cols == this.cols, "Matrices must have same number of columns")

    if(isTranspose) {
      return this.t.writeFromDense(b.t)
    }

    val _b = if(b.isTranspose) b.copy else b

    val bPtr = cuda.cuPointerToArray(_b.data)

    val (width, height) = if(isTranspose) (cols, rows) else (rows, cols)

    assert(majorStride >= width, majorStride + " " + width)
    assert(_b.majorStride >= width)

    JCuda.cudaMemcpy2D(data.toCuPointer.withByteOffset(offset * elemSize),
      majorStride * elemSize,
      bPtr.withByteOffset(offset * elemSize),
      _b.majorStride * elemSize,
      width * elemSize,
      height,
      cudaMemcpyKind.cudaMemcpyHostToDevice
    )

    JCuda.cudaFreeHost(bPtr)

  }

  private def isGapless = (!this.isTranspose && this.majorStride == this.rows) || (this.isTranspose && this.majorStride == this.cols)


  def writeFrom(b: CuMatrix[V])(implicit stream: CUstream = new CUstream()) = {
    require(b.rows == this.rows, "Matrices must have same number of rows")
    require(b.cols == this.cols, "Matrices must have same number of columns")

    val aPtr = data.toCuPointer.withByteOffset(offset * elemSize)
    val bPtr = b.data.toCuPointer.withByteOffset(offset * elemSize)

    val (width, height) = if(isTranspose) (cols, rows) else (rows, cols)

    if(b.isGapless && this.isGapless && b.isTranspose == this.isTranspose)  {
      JCuda.cudaMemcpyAsync(aPtr, bPtr, size * elemSize, cudaMemcpyKind.cudaMemcpyDeviceToDevice, new cudaStream_t(stream))
    } else if(b.isTranspose == this.isTranspose) {
      JCuda.cudaMemcpy2DAsync(aPtr,
        majorStride * elemSize,
        bPtr,
        b.majorStride * elemSize,
        width * elemSize,
        height,
        cudaMemcpyKind.cudaMemcpyDeviceToDevice,
        new cudaStream_t(stream)
      )

    } else {
      val op = if(elemSize == 4) {
        JCublas2.cublasSgeam _
      } else if(elemSize == 8) {
        JCublas2.cublasDgeam _
      } else {
        throw new UnsupportedOperationException("can't do a copy-transpose with elems that are not of size 4 or 8")
      }

      blas.withStream(stream) {
        op(blas, cublasOperation.CUBLAS_OP_T, cublasOperation.CUBLAS_OP_T,
          width, height,
          CuMatrix.hostOne,
          bPtr,
          b.majorStride,
          CuMatrix.hostZero,
          bPtr, b.majorStride, aPtr, majorStride)
      }


    }



  }


  private def canReshapeView = if(isTranspose) majorStride == cols else majorStride == rows

  /** Reshapes this matrix to have the given number of rows and columns
    * If view = true (or View.Require), throws an exception if we cannot return a view. otherwise returns a view.
    * If view == false (or View.Copy) returns a copy
    * If view == View.Prefer (the default), returns a view if possible, otherwise returns a copy.
    *
    * Views are only possible (if(isTranspose) majorStride == cols else majorStride == rows) == true
    *
    * rows * cols must equal size, or cols < 0 && (size / rows * rows == size)
    * @param rows the number of rows
    * @param cols the number of columns, or -1 to auto determine based on size and rows
    */
  def reshape(rows: Int, cols: Int, view: View=View.Prefer):CuMatrix[V] = {
    val _cols = cols//if(cols < 0) size / rows else cols
    require(rows * _cols == size, "Cannot reshape a (%d,%d) matrix to a (%d,%d) matrix!".format(this.rows, this.cols, rows, _cols))

    view match {
      case View.Require =>
        if(!canReshapeView)
          throw new UnsupportedOperationException("Cannot make a view of this matrix.")
        else
          new CuMatrix(rows, _cols, data, offset, if(isTranspose) cols else rows, isTranspose)
      case View.Copy =>
        // calling copy directly gives a verify error. TODO: submit bug
        val result = copy
        result.reshape(rows, _cols, View.Require)
      case View.Prefer =>
        reshape(rows, cols, canReshapeView)
    }
  }

  /*
  def assignAsync(b: V)(implicit stream: CUstream = new CUstream(), cast: CanRepresentAs[V, Int]) = {
    require(elemSize == 4)
    val (width, height) = if(isTranspose) (cols, rows) else (rows, cols)
    JCuda.cudaMemset2DAsync(data.toCuPointer, majorStride, cast.convert(b), width, height, stream)
  }
  */

  /** Forcibly releases the buffer. Note that other slices will be invalidated! */
  def release() = {
    data.release()
  }

  def toDense = {
    val arrayData = Pointer.allocateArray(data.getIO, size)

    val (_r, _c) = if(isTranspose) (cols, rows) else (rows, cols)

    JCublas2.cublasGetMatrix(_r, _c, elemSize.toInt, data.toCuPointer.withByteOffset(elemSize * offset), majorStride, arrayData.toCuPointer, _r)

    new DenseMatrix(rows, cols, arrayData.getArray.asInstanceOf[Array[V]], 0, _r, isTranspose)
  }

  def copy: CuMatrix[V] = ???


  /**
   * Method for slicing that is tuned for Matrices.
   * @return
   */
  def apply[Slice1, Slice2, Result](slice1: Slice1, slice2: Slice2)(implicit canSlice: CanSlice2[CuMatrix[V], Slice1, Slice2, Result]) = {
    canSlice(repr, slice1, slice2)
  }


}

object CuMatrix extends LowPriorityNativeMatrix with CuMatrixOps with CuMatrixSliceOps with CuMatrixFuns {

  /**
   * The standard way to create an empty matrix, size is rows * cols
   */
  def zeros[V](rows: Int, cols: Int)(implicit ct: ClassTag[V], blas: cublasHandle): CuMatrix[V] = {
    val mat = new CuMatrix[V](rows, cols)

    JCuda.cudaMemset(mat.data.toCuPointer, 0, mat.size * mat.elemSize)

    mat
  }

  /**
   * Doesn't zero the matrix.
   */
  def create[V](rows: Int, cols: Int)(implicit ct: ClassTag[V], blas: cublasHandle): CuMatrix[V] = {
    val mat = new CuMatrix[V](rows, cols)
    JCuda.cudaMemset(mat.data.toCuPointer, 0, mat.size * mat.elemSize)

    mat
  }

  def rand(rows: Int, cols: Int)(implicit blas: cublasHandle) = {
    import jcuda.jcurand.JCurand._
    val mat = new CuMatrix[Float](rows, cols)
    val generator = new curandGenerator()
    curandCreateGenerator(generator, curandRngType.CURAND_RNG_PSEUDO_DEFAULT)
    curandSetPseudoRandomGeneratorSeed(generator, 1234)

    curandGenerateUniform(generator, mat.data.toCuPointer, rows * cols)
    curandDestroyGenerator(generator)

    mat
  }


  def fromDense[V<:AnyVal](mat: DenseMatrix[V])(implicit ct: ClassTag[V], blas: cublasHandle) = {
    val g = new CuMatrix[V](mat.rows, mat.cols)
    g := mat
    g
  }


  /*


  // slices
  implicit def canSliceRow[V:ClassTag]: CanSlice2[CuMatrix[V], Int, ::.type, CuMatrix[V]] = {
    new CanSlice2[CuMatrix[V], Int, ::.type, CuMatrix[V]] {
      def apply(m: CuMatrix[V], row: Int, ignored: ::.type) = {
        import m.queue
        if(row < 0 || row >= m.rows) throw new ArrayIndexOutOfBoundsException("Row must be in bounds for slice!")
        if(!m.isTranspose)
          new CuMatrix(1, m.cols, m.data, m.offset + row, m.majorStride)
        else
          new CuMatrix(1, m.cols, m.data, m.offset + row * m.cols, 1)
      }
    }
  }

  implicit def canSliceCol[V:ClassTag]: CanSlice2[CuMatrix[V], ::.type, Int, CuMatrix[V]] = {
    new CanSlice2[CuMatrix[V], ::.type, Int, CuMatrix[V]] {
      def apply(m: CuMatrix[V], ignored: ::.type, col: Int) = {
        import m.queue
        if(col < 0 || col >= m.cols) throw new ArrayIndexOutOfBoundsException("Column must be in bounds for slice!")
        if(!m.isTranspose)
          new CuMatrix(m.rows, 1, m.data, col * m.majorStride + m.offset)
        else
          new CuMatrix(1, m.cols, m.data, offset = m.offset + col, majorStride = m.majorStride)
      }
    }
  }

  implicit def canSliceRows[V:ClassTag]: CanSlice2[CuMatrix[V], Range, ::.type, CuMatrix[V]] = {
    new CanSlice2[CuMatrix[V], Range, ::.type, CuMatrix[V]] {
      def apply(m: CuMatrix[V], rows: Range, ignored: ::.type) = {
        import m.queue
        if(rows.isEmpty) new CuMatrix(0, 0, m.data, 0, 0)
        else if(!m.isTranspose) {
          assert(rows.head >= 0)
          assert(rows.last < m.rows, s"last row ${rows.last} is bigger than rows ${m.rows}")
          require(rows.step == 1, "Sorry, we can't support row ranges with step sizes other than 1")
          val first = rows.head
          new CuMatrix(rows.length, m.cols, m.data, m.offset + first, m.majorStride)
        } else {
          assert(rows.head >= 0)
          assert(rows.last < m.rows)
          canSliceCols.apply (m.t, ::, rows).t
        }
      }
    }
  }

  implicit def canSliceCols[V:ClassTag]: CanSlice2[CuMatrix[V], ::.type, Range, CuMatrix[V]] = {
    new CanSlice2[CuMatrix[V], ::.type, Range, CuMatrix[V]] {
      def apply(m: CuMatrix[V], ignored: ::.type, cols: Range) = {
        import m.queue
        if(cols.isEmpty) new CuMatrix(m.rows, 0, m.data, 0, 1)
        else if(!m.isTranspose) {
          assert(cols.head >= 0)
          assert(cols.last < m.cols, cols.last + " " + m.cols)
          val first = cols.head
          new CuMatrix(m.rows, cols.length, m.data, m.offset + first * m.majorStride, m.majorStride * cols.step)
        } else {
          canSliceRows.apply(m.t, cols, ::).t
        }
      }
    }
  }

  implicit def canSliceColsAndRows[V:ClassTag]: CanSlice2[CuMatrix[V], Range, Range, CuMatrix[V]] = {
    new CanSlice2[CuMatrix[V], Range, Range, CuMatrix[V]] {
      def apply(m: CuMatrix[V], rows: Range, cols: Range) = {
        import m.queue
        if(rows.isEmpty || cols.isEmpty) new CuMatrix(0, 0, m.data, 0, 1)
        else if(!m.isTranspose) {
          assert(cols.head >= 0)
          assert(cols.last < m.cols)
          assert(rows.head >= 0)
          assert(rows.last < m.rows)
          require(rows.step == 1, "Sorry, we can't support row ranges with step sizes other than 1 for non transposed matrices")
          val first = cols.head
          new CuMatrix(rows.length, cols.length, m.data, m.offset + first * m.rows + rows.head, m.majorStride * cols.step)(m.queue, implicitly)
        } else {
          require(cols.step == 1, "Sorry, we can't support col ranges with step sizes other than 1 for transposed matrices")
          canSliceColsAndRows.apply(m.t, cols, rows).t
        }
      }
    }
  }



  implicit def canSlicePartOfRow[V:ClassTag]: CanSlice2[CuMatrix[V], Int, Range, CuMatrix[V]] = {
    new CanSlice2[CuMatrix[V], Int, Range, CuMatrix[V]] {
      def apply(m: CuMatrix[V], row: Int, cols: Range) = {
        import m.queue
        if(row < 0  || row > m.rows) throw new IndexOutOfBoundsException("Slice with out of bounds row! " + row)
        if(cols.isEmpty) new CuMatrix(0, 0, m.data, 0, 1)
        else if(!m.isTranspose) {
          val first = cols.head
          new CuMatrix(1, cols.length, m.data, m.offset + first * m.rows + row, m.majorStride * cols.step)
        } else {
          require(cols.step == 1, "Sorry, we can't support col ranges with step sizes other than 1 for transposed matrices")
          canSlicePartOfCol.apply(m.t, cols, row).t
        }
      }
    }
  }

  implicit def canSlicePartOfCol[V:ClassTag]: CanSlice2[CuMatrix[V], Range, Int, CuMatrix[V]] = {
    new CanSlice2[CuMatrix[V], Range, Int, CuMatrix[V]] {
      def apply(m: CuMatrix[V], rows: Range, col: Int) = {
        import m.queue
        if(rows.isEmpty) new CuMatrix(0, 0, m.data, 0)
        else if(!m.isTranspose) {
          new CuMatrix(col * m.rows + m.offset + rows.head, 1, m.data, rows.step, rows.length)
        } else {
          val m2 = canSlicePartOfRow.apply(m.t, col, rows).t
          m2(::, 0)
        }
      }
    }
  }

  /*
  implicit def canMapValues[V, R:ClassTag] = {
    new CanMapValues[CuMatrix[V],V,R,CuMatrix[R]] {
      override def map(from : CuMatrix[V], fn : (V=>R)) = {
        val data = new Array[R](from.size)
        var j = 0
        var off = 0
        while (j < from.cols) {
          var i = 0
          while(i < from.rows) {
            data(off) = fn(from(i, j))
            off += 1
            i += 1
          }
          j += 1
        }
        new CuMatrix[R](from.rows, from.cols, data)
      }

      override def mapActive(from : CuMatrix[V], fn : (V=>R)) =
        map(from, fn)
    }
  }


  implicit def canTransformValues[V]:CanTransformValues[CuMatrix[V], V, V] = {
    new CanTransformValues[CuMatrix[V], V, V] {
      def transform(from: CuMatrix[V], fn: (V) => V) {
        var j = 0
        while (j < from.cols) {
          var i = 0
          while(i < from.rows) {
            from(i, j) = fn(from(i, j))
            i += 1
          }
          j += 1
        }
      }

      def transformActive(from: CuMatrix[V], fn: (V) => V) {
        transform(from, fn)
      }
    }
  }

  implicit def canMapKeyValuePairs[V, R:ClassTag] = {
    new CanMapKeyValuePairs[CuMatrix[V],(Int,Int),V,R,CuMatrix[R]] {
      override def map(from : CuMatrix[V], fn : (((Int,Int),V)=>R)) = {
        val data = new Array[R](from.data.length)
        var j = 0
        var off = 0
        while (j < from.cols) {
          var i = 0
          while(i < from.rows) {
            data(off) = fn(i -> j, from(i, j))
            off += 1
            i += 1
          }
          j += 1
        }
        new CuMatrix(from.rows, from.cols, data)
      }

      override def mapActive(from : CuMatrix[V], fn : (((Int,Int),V)=>R)) =
        map(from, fn)
    }
  }
  */
  */

  implicit def canTranspose[V]: CanTranspose[CuMatrix[V], CuMatrix[V]] = {
    new CanTranspose[CuMatrix[V], CuMatrix[V]] {
      def apply(from: CuMatrix[V]) = {
        new CuMatrix(data = from.data, offset = from.offset, cols = from.rows, rows = from.cols, majorStride = from.majorStride, isTranspose = !from.isTranspose)(from.blas)
      }
    }
  }

  /*
  implicit def canTransposeComplex: CanTranspose[CuMatrix[Complex], CuMatrix[Complex]] = {
    new CanTranspose[CuMatrix[Complex], CuMatrix[Complex]] {
      def apply(from: CuMatrix[Complex]) = {
        new CuMatrix(data = from.data map { _.conjugate },
          offset = from.offset,
          cols = from.rows,
          rows = from.cols,
          majorStride = from.majorStride,
          isTranspose = !from.isTranspose)
      }
    }
  }
  */


  /**
   * Maps the columns into a new dense matrix
   * @tparam V
   * @tparam R
   * @return
  implicit def canMapRows[V:ClassTag:DefaultArrayValue]: CanCollapseAxis[CuMatrix[V], Axis._0.type, CuMatrix[V], CuMatrix[V], CuMatrix[V]]  = new CanCollapseAxis[CuMatrix[V], Axis._0.type, CuMatrix[V], CuMatrix[V], CuMatrix[V]] {
    def apply(from: CuMatrix[V], axis: Axis._0.type)(f: (CuMatrix[V]) => CuMatrix[V]): CuMatrix[V] = {
      var result:CuMatrix[V] = null
      for(c <- 0 until from.cols) {
        val col = f(from(::, c))
        if(result eq null) {
          result = CuMatrix.zeros[V](col.length, from.cols)
        }
        result(::, c) := col
      }
      if(result eq null){
        CuMatrix.zeros[V](0, from.cols)
      } else {
        result
      }
    }
  }

  /**
   * Returns a numRows CuMatrix
   * @tparam V
   * @tparam R
   * @return
   */
  implicit def canMapCols[V:ClassTag:DefaultArrayValue] = new CanCollapseAxis[CuMatrix[V], Axis._1.type, CuMatrix[V], CuMatrix[V], CuMatrix[V]] {
    def apply(from: CuMatrix[V], axis: Axis._1.type)(f: (CuMatrix[V]) => CuMatrix[V]): CuMatrix[V] = {
      var result:CuMatrix[V] = null
      val t = from.t
      for(r <- 0 until from.rows) {
        val row = f(t(::, r))
        if(result eq null) {
          result = CuMatrix.zeros[V](from.rows, row.length)
        }
        result.t apply (::, r) := row
      }
      result
    }
  }



/*
  implicit def canGaxpy[V: Semiring]: CanAxpy[V, CuMatrix[V], CuMatrix[V]] = {
    new CanAxpy[V, CuMatrix[V], CuMatrix[V]] {
      val ring = implicitly[Semiring[V]]
      def apply(s: V, b: CuMatrix[V], a: CuMatrix[V]) {
        require(a.rows == b.rows, "Vector row dimensions must match!")
        require(a.cols == b.cols, "Vector col dimensions must match!")

        var i = 0
        while (i < a.rows) {
          var j = 0
          while (j < a.cols) {
            a(i, j) = ring.+(a(i, j), ring.*(s, b(i, j)))
            j += 1
          }
          i += 1
        }
      }
    }
  }
   */
   */



  /*
  implicit def setMM[V](implicit stream: CUstream = new CUstream()): OpSet.InPlaceImpl2[CuMatrix[V], CuMatrix[V]] = new OpSet.InPlaceImpl2[CuMatrix[V], CuMatrix[V]] {
    def apply(v: CuMatrix[V], v2: CuMatrix[V]): Unit = {
      v.writeFrom(v2)
    }
  }

  implicit def setMDM[V](implicit stream: CUstream = new CUstream()): OpSet.InPlaceImpl2[CuMatrix[V], DenseMatrix[V]] = new OpSet.InPlaceImpl2[CuMatrix[V], DenseMatrix[V]] {
    def apply(v: CuMatrix[V], v2: DenseMatrix[V]): Unit = {
      v.writeFromDense(v2)
    }
  }
  */

  protected val hostOnePtr = Pointer.pointerToFloat(1)

  protected val hostOne = hostOnePtr.toCuPointer


  protected val hostZeroPtr = Pointer.pointerToFloat(0)

  protected val hostZero = hostZeroPtr.toCuPointer
}

trait LowPriorityNativeMatrix1 {
  //  class SetMMOp[@specialized(Int, Double, Float) V] extends BinaryUpdateOp[CuMatrix[V], Matrix[V], OpSet] {
  //    def apply(a: CuMatrix[V], b: Matrix[V]) {
  //      require(a.rows == b.rows, "Matrixs must have same number of rows")
  //      require(a.cols == b.cols, "Matrixs must have same number of columns")
  //
  //      // slow path when we don't have a trivial matrix
  //      val ad = a.data
  //      var c = 0
  //      while(c < a.cols) {
  //        var r = 0
  //        while(r < a.rows) {
  //          ad(a.linearIndex(r, c)) = b(r, c)
  //          r += 1
  //        }
  //        c += 1
  //      }
  //    }
  //  }



  //  class SetDMVOp[@specialized(Int, Double, Float) V] extends BinaryUpdateOp[CuMatrix[V], Vector[V], OpSet] {
  //    def apply(a: CuMatrix[V], b: Vector[V]) {
  //      require(a.rows == b.length && a.cols == 1 || a.cols == b.length && a.rows == 1, "CuMatrix must have same number of rows, or same number of columns, as CuMatrix, and the other dim must be 1.")
  //      val ad = a.data
  //      var i = 0
  //      var c = 0
  //      while(c < a.cols) {
  //        var r = 0
  //        while(r < a.rows) {
  //          ad(a.linearIndex(r, c)) = b(i)
  //          r += 1
  //          i += 1
  //        }
  //        c += 1
  //      }
  //    }
  //  }
  //
  //  implicit def setMM[V]: BinaryUpdateOp[CuMatrix[V], Matrix[V], OpSet] = new SetMMOp[V]
  //  implicit def setMV[V]: BinaryUpdateOp[CuMatrix[V], Vector[V], OpSet] = new SetDMVOp[V]
}

trait LowPriorityNativeMatrix extends LowPriorityNativeMatrix1 { this: CuMatrix.type =>

  class SetCuMCuMVOp[V] extends OpSet.InPlaceImpl2[CuMatrix[V], CuMatrix[V]] {
    def apply(a: CuMatrix[V], b: CuMatrix[V]) {
      a.writeFrom(b.asInstanceOf[CuMatrix[V]])
    }
  }

  implicit def SetCuMDMOp[V]: OpSet.InPlaceImpl2[CuMatrix[V], DenseMatrix[V]] = new  OpSet.InPlaceImpl2[CuMatrix[V], DenseMatrix[V]] {
    def apply(a: CuMatrix[V], b: DenseMatrix[V]) {
      a.writeFromDense(b)
    }
  }



  implicit object setCuMCuMFloat extends SetCuMCuMVOp[Float]
  implicit object setCuMCuMLong extends SetCuMCuMVOp[Long]
  implicit object setCuMCuMInt extends SetCuMCuMVOp[Int]
  implicit object setCuMCuMDouble extends SetCuMCuMVOp[Double]

  /*
  class SetDMDVOp[@specialized(Int, Double, Float) V] extends BinaryUpdateOp[CuMatrix[V], CuMatrix[V], OpSet] {
    def apply(a: CuMatrix[V], b: CuMatrix[V]) {
      require(a.rows == b.length && a.cols == 1 || a.cols == b.length && a.rows == 1, "CuMatrix must have same number of rows, or same number of columns, as CuMatrix, and the other dim must be 1.")
      val ad = a.data
      val bd = b.data
      var c = 0
      var boff = b.offset
      while(c < a.cols) {
        var r = 0
        while(r < a.rows) {
          ad(a.linearIndex(r, c)) = bd(boff)
          r += 1
          boff += b.stride
        }
        c += 1
      }
    }
  }


  implicit object SetMSFloatOp extends OpSet.InPlaceImpl2[CuMatrix[Float], Float] {
    def apply(a: CuMatrix[Float], b: Float) {
      val zmk = ZeroMemoryKernel()(a.queue.getContext)
      import a.queue
      // nicely shaped matrix
      if( (!a.isTranspose && a.majorStride == a.rows)  ||(a.isTranspose && a.majorStride == a.cols)) {
        val ev = zmk.fillMemory(a.data, b, a.offset, a.rows * a.cols)
        ev.waitFor()
      } else {
        zmk.shapedFill(a, b).waitFor()
      }
    }
  }

  implicit object SetMSIntOp extends OpSet.InPlaceImpl2[CuMatrix[Int], Int] {
    def apply(a: CuMatrix[Int], b: Int) {
      val zmk = ZeroMemoryKernel()(a.queue.getContext)
      import a.queue
      // nicely shaped matrix
      if( (!a.isTranspose && a.majorStride == a.rows)  ||(a.isTranspose && a.majorStride == a.cols)) {
        val ev = zmk.fillMemory(a.data.asCLFloatBuffer(), java.lang.Float.intBitsToFloat(b), a.offset, a.rows * a.cols)
        ev.waitFor()
      } else {
        zmk.shapedFill(a.asInstanceOf[CuMatrix[Float]], java.lang.Float.intBitsToFloat(b)).waitFor()
      }
    }
  }

  */

   def transposeOp(a: CuMatrix[_]): Int = {
    if (a.isTranspose) cublasOperation.CUBLAS_OP_T else cublasOperation.CUBLAS_OP_N
  }


}

trait CuMatrixOps { this: CuMatrix.type =>
  implicit object CuMatrixDMulCuMatrixD
    extends OpMulMatrix.Impl2[CuMatrix[Double], CuMatrix[Double], CuMatrix[Double]] {
    def apply(_a : CuMatrix[Double], _b : CuMatrix[Double]): CuMatrix[Double] = {
      import _a.blas
      require(_a.cols == _b.rows, "Dimension mismatch!")
      val rv = CuMatrix.zeros[Double](_a.rows, _b.cols)

      if(_a.rows == 0 || _b.rows == 0 || _a.cols == 0 || _b.cols == 0) return rv

      // if we have a weird stride...
      val a:CuMatrix[Double] = if(_a.majorStride < math.max(if(_a.isTranspose) _a.cols else _a.rows, 1)) _a.copy else _a
      val b:CuMatrix[Double] = if(_b.majorStride < math.max(if(_b.isTranspose) _b.cols else _b.rows, 1)) _b.copy else _b

      JCublas2.cublasDgemm(_a.blas, transposeOp(a), transposeOp(b),
        rv.rows, rv.cols, a.cols,
        hostOne, a.data.toCuPointer.withByteOffset(a.offset * a.elemSize), a.majorStride,
        b.data.toCuPointer.withByteOffset(b.offset * b.elemSize), b.majorStride,
        hostZero, rv.data.toCuPointer, rv.rows)
      rv
    }
  }

  implicit object CuMatrixFMulCuMatrixF
    extends OpMulMatrix.Impl2[CuMatrix[Float], CuMatrix[Float], CuMatrix[Float]] {
    def apply(_a : CuMatrix[Float], _b : CuMatrix[Float]): CuMatrix[Float] = {
      import _a.blas
      require(_a.cols == _b.rows, "Dimension mismatch!")
      val rv = CuMatrix.zeros[Float](_a.rows, _b.cols)

      if(_a.rows == 0 || _b.rows == 0 || _a.cols == 0 || _b.cols == 0) return rv

      // if we have a weird stride...
      val a:CuMatrix[Float] = if(_a.majorStride < math.max(if(_a.isTranspose) _a.cols else _a.rows, 1)) _a.copy else _a
      val b:CuMatrix[Float] = if(_b.majorStride < math.max(if(_b.isTranspose) _b.cols else _b.rows, 1)) _b.copy else _b

      JCublas2.cublasSgemm(_a.blas, transposeOp(a), transposeOp(b),
        rv.rows, rv.cols, a.cols,
        hostOne, a.data.toCuPointer.withByteOffset(a.offset * a.elemSize), a.majorStride,
        b.data.toCuPointer.withByteOffset(b.offset * b.elemSize), b.majorStride,
        hostZero, rv.data.toCuPointer, rv.rows)
      rv
    }
  }
}



trait CuMatrixSliceOps { this: CuMatrix.type =>
  implicit def canSliceRow[V]: CanSlice2[CuMatrix[V], Int, ::.type, CuMatrix[V]] = {
    new CanSlice2[CuMatrix[V], Int, ::.type, CuMatrix[V]] {
      def apply(m: CuMatrix[V], rowWNegative: Int, ignored: ::.type) = {
        import m.blas

        if(rowWNegative < -m.rows || rowWNegative >= m.rows) throw new ArrayIndexOutOfBoundsException("Row must be in bounds for slice!")
        val row = if(rowWNegative<0) rowWNegative+m.rows else rowWNegative

        if(!m.isTranspose)
          new CuMatrix(1, m.cols, m.data, m.offset + row, m.majorStride)
        else
          new CuMatrix(1, m.cols, m.data, m.offset + row * m.cols, 1)
      }
    }
  }

  implicit def canSliceCol[V]: CanSlice2[CuMatrix[V], ::.type, Int, CuMatrix[V]] = {
    new CanSlice2[CuMatrix[V], ::.type, Int, CuMatrix[V]] {
      def apply(m: CuMatrix[V], ignored: ::.type, colWNegative: Int) = {
        import m.blas

        if(colWNegative < -m.cols || colWNegative >= m.cols) throw new ArrayIndexOutOfBoundsException("Column must be in bounds for slice!")
        val col = if(colWNegative<0) colWNegative+m.cols else colWNegative

        if(!m.isTranspose)
          new CuMatrix(m.rows, 1, m.data, col * m.rows + m.offset, m.majorStride)
        else
          new CuMatrix(rows=m.rows, 1, m.data, offset = m.offset + col, majorStride = m.majorStride, true)
      }
    }
  }

  implicit def canSliceRows[V]: CanSlice2[CuMatrix[V], Range, ::.type, CuMatrix[V]] = {
    new CanSlice2[CuMatrix[V], Range, ::.type, CuMatrix[V]] {
      def apply(m: CuMatrix[V], rowsWNegative: Range, ignored: ::.type) = {
        import m.blas

        val rows = rowsWNegative.getRangeWithoutNegativeIndexes(m.rows)

        if(rows.isEmpty) new CuMatrix(0, m.cols, m.data, 0, 0)
        else if(!m.isTranspose) {
          require(rows.step == 1, "Sorry, we can't support row ranges with step sizes other than 1")
          val first = rows.head
          require(rows.last < m.rows)
          if(rows.last >= m.rows) {
            throw new IndexOutOfBoundsException(s"Row slice of $rows was bigger than matrix rows of ${m.rows}")
          }
          new CuMatrix(rows.length, m.cols, m.data, m.offset + first, m.majorStride)
        } else {
          canSliceCols(m.t, ::, rows).t
        }
      }
    }
  }

  implicit def canSliceCols[V]: CanSlice2[CuMatrix[V], ::.type, Range, CuMatrix[V]] = {
    new CanSlice2[CuMatrix[V], ::.type, Range, CuMatrix[V]] {
      def apply(m: CuMatrix[V], ignored: ::.type, colsWNegative: Range) = {
        import m.blas

        val cols = colsWNegative.getRangeWithoutNegativeIndexes(m.cols)

        if(cols.isEmpty) new CuMatrix(m.rows, 0, m.data, 0, 1)
        else if(!m.isTranspose) {
          val first = cols.head
          if(cols.last >= m.cols) {
            throw new IndexOutOfBoundsException(s"Col slice of $cols was bigger than matrix cols of ${m.cols}")
          }
          new CuMatrix(m.rows, cols.length, m.data, m.offset + first * m.majorStride, m.majorStride * cols.step)
        } else {
          canSliceRows(m.t, cols, ::).t
        }
      }
    }
  }

  implicit def canSliceColsAndRows[V]: CanSlice2[CuMatrix[V], Range, Range, CuMatrix[V]] = {
    new CanSlice2[CuMatrix[V], Range, Range, CuMatrix[V]] {
      def apply(m: CuMatrix[V], rowsWNegative: Range, colsWNegative: Range) = {
        import m.blas

        val rows = rowsWNegative.getRangeWithoutNegativeIndexes(m.rows)
        val cols = colsWNegative.getRangeWithoutNegativeIndexes(m.cols)

        if(rows.isEmpty || cols.isEmpty) new CuMatrix(rows.size, cols.size, m.data, 0, 1)
        else if(!m.isTranspose) {
          require(rows.step == 1, "Sorry, we can't support row ranges with step sizes other than 1 for non transposed matrices")
          val first = cols.head
          if(rows.last >= m.rows) {
            throw new IndexOutOfBoundsException(s"Row slice of $rows was bigger than matrix rows of ${m.rows}")
          }
          if(cols.last >= m.cols) {
            throw new IndexOutOfBoundsException(s"Col slice of $cols was bigger than matrix cols of ${m.cols}")
          }
          new CuMatrix(rows.length, cols.length, m.data, m.offset + first * m.rows + rows.head, m.majorStride * cols.step)
        } else {
          require(cols.step == 1, "Sorry, we can't support col ranges with step sizes other than 1 for transposed matrices")
          canSliceColsAndRows(m.t, cols, rows).t
        }
      }
    }
  }



  implicit def negFromScale[V](implicit scale: OpMulScalar.Impl2[CuMatrix[V], V, CuMatrix[V]], field: Ring[V]) = {
    new OpNeg.Impl[CuMatrix[V], CuMatrix[V]] {
      override def apply(a : CuMatrix[V]) = {
        scale(a, field.negate(field.one))
      }
    }
  }

  implicit def canSlicePartOfRow[V]: CanSlice2[CuMatrix[V], Int, Range, CuMatrix[V]] = {
    new CanSlice2[CuMatrix[V], Int, Range, CuMatrix[V]] {
      def apply(m: CuMatrix[V], rowWNegative: Int, colsWNegative: Range) = {
        import m.blas

        if(rowWNegative < -m.rows || rowWNegative >= m.rows) throw new ArrayIndexOutOfBoundsException("Row must be in bounds for slice!")
        val row = if(rowWNegative<0) rowWNegative + m.rows else rowWNegative
        val cols = colsWNegative.getRangeWithoutNegativeIndexes(m.cols)

        if(row < 0  || row > m.rows) throw new IndexOutOfBoundsException("Slice with out of bounds row! " + row)
        if(cols.isEmpty) new CuMatrix(0, 0, m.data, 0, 1)
        else if(!m.isTranspose) {
          val first = cols.head
          if(cols.last >= m.cols) {
            throw new IndexOutOfBoundsException(s"Col slice of $cols was bigger than matrix cols of ${m.cols}")
          }
          new CuMatrix(1, cols.length, m.data, m.offset + first * m.rows + row, m.majorStride * cols.step)
        } else {
          require(cols.step == 1, "Sorry, we can't support col ranges with step sizes other than 1 for transposed matrices")
          canSlicePartOfCol(m.t, cols, row).t
        }
      }
    }
  }

  implicit def canSlicePartOfCol[V]: CanSlice2[CuMatrix[V], Range, Int, CuMatrix[V]] = {
    new CanSlice2[CuMatrix[V], Range, Int, CuMatrix[V]] {
      def apply(m: CuMatrix[V], rowsWNegative: Range, colWNegative: Int) = {
        import m.blas

        val rows = rowsWNegative.getRangeWithoutNegativeIndexes(m.rows)
        if(colWNegative < -m.cols || colWNegative >= m.cols) throw new ArrayIndexOutOfBoundsException("Row must be in bounds for slice!")
        val col = if(colWNegative<0) colWNegative + m.cols else colWNegative

        if(rows.isEmpty) new CuMatrix(0, 0, m.data)
        else if(!m.isTranspose) {
          if(rows.last >= m.rows) {
            throw new IndexOutOfBoundsException(s"Row slice of $rows was bigger than matrix rows of ${m.rows}")
          }
          new CuMatrix(rows.length, 1, m.data, col * m.rows + m.offset + rows.head, m.majorStride)
        } else {
          val m2 = canSlicePartOfRow(m.t, col, rows).t
          m2(::, 0)
        }
      }
    }
  }

}

trait CuMatrixFuns {

  implicit val kernelsFloat = new CuMapKernels[CuMatrix[Float], Float]("float")

  implicit def acosImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[acos.type]("acos")
  implicit def asinImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[asin.type]("asin")
  implicit def atanImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[atan.type]("atan")

  implicit def acoshImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[acosh.type]("acosh")
  implicit def asinhImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[asinh.type]("asinh")
  implicit def atanhImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[atanh.type]("atanh")

  implicit def cosImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[cos.type]("cos")
  implicit def sinImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[sin.type]("sin")
  implicit def tanImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[tan.type]("tan")

  implicit def coshImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[cosh.type]("cosh")
  implicit def sinhImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[sinh.type]("sinh")
  implicit def tanhImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[tanh.type]("tanh")

  implicit def cbrtImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[cbrt.type]("cbrt")
  implicit def ceilImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[ceil.type]("ceil")
//  implicit def cospiImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[cospi.type]("cospi")
  implicit def erfcImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[erfc.type]("erfc")
  implicit def erfcinvImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[erfcinv.type]("erfcinv")
  implicit def erfImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[erf.type]("erf")
  implicit def erfinvImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[erfinv.type]("erfinv")
  implicit def expImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[exp.type]("exp")
  implicit def expm1Impl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[expm1.type]("expm1")
  implicit def fabsImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[abs.type]("fabs")
  implicit def floorImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[floor.type]("floor")
  implicit def j0Impl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[Bessel.i0.type]("j0")
  implicit def j1Impl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[Bessel.i1.type]("j1")
  implicit def lgammaImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[lgamma.type]("lgamma")
  implicit def log10Impl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[log10.type]("log10")
  implicit def log1pImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[log1p.type]("log1p")
//  implicit def log2Impl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[log2.type]("log2")
//  implicit def logbImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[logb.type]("logb")
  implicit def logImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[log.type]("log")
  implicit def sqrtImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[sqrt.type]("sqrt")
  implicit def rintImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[rint.type]("rint")
//  implicit def truncImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.implFor[trunc.type]("trunc")

  implicit def acosIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[acos.type]("acos")
  implicit def asinIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[asin.type]("asin")
  implicit def atanIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[atan.type]("atan")

  implicit def acoshIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[acosh.type]("acosh")
  implicit def asinhIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[asinh.type]("asinh")
  implicit def atanhIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[atanh.type]("atanh")

  implicit def cosIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[cos.type]("cos")
  implicit def sinIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[sin.type]("sin")
  implicit def tanIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[tan.type]("tan")

  implicit def coshIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[cosh.type]("cosh")
  implicit def sinhIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[sinh.type]("sinh")
  implicit def tanhIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[tanh.type]("tanh")

  implicit def cbrtIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[cbrt.type]("cbrt")
  implicit def ceilIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[ceil.type]("ceil")
  //  implicit def cospiIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[cospi.type]("cospi")
  implicit def erfcIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[erfc.type]("erfc")
  implicit def erfcinvIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[erfcinv.type]("erfcinv")
  implicit def erfIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[erf.type]("erf")
  implicit def erfinvIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[erfinv.type]("erfinv")
  implicit def expIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[exp.type]("exp")
  implicit def expm1IntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[expm1.type]("expm1")
  implicit def fabsIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[abs.type]("fabs")
  implicit def floorIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[floor.type]("floor")
  implicit def j0IntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[Bessel.i0.type]("j0")
  implicit def j1IntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[Bessel.i1.type]("j1")
  implicit def lgammaIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[lgamma.type]("lgamma")
  implicit def log10IntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[log10.type]("log10")
  implicit def log1pIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[log1p.type]("log1p")
  //  implicit def log2IntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[log2.type]("log2")
  //  implicit def logbIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[logb.type]("logb")
  implicit def logIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[log.type]("log")
  implicit def sqrtIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[sqrt.type]("sqrt")
  implicit def rintIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImplFor[rint.type]("rint")


  implicit def addImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For[OpAdd.type]("add")
  implicit def subImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For[OpSub.type]("sub")
  implicit def mulImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For[OpMulScalar.type]("mul")
  implicit def divImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For[OpDiv.type]("div")
  implicit def modImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For[OpMod.type]("mod")
  implicit def maxImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For[max.type]("max")
  implicit def minImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For[min.type]("min")
  implicit def powImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For[OpPow.type]("pow")

  implicit def addIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImpl2For[OpAdd.type]("add")
  implicit def subIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImpl2For[OpSub.type]("sub")
  implicit def mulIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImpl2For[OpMulScalar.type]("mul")
  implicit def divIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImpl2For[OpDiv.type]("div")
  implicit def modIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImpl2For[OpMod.type]("mod")
  implicit def maxIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImpl2For[max.type]("max")
  implicit def minIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImpl2For[min.type]("min")
  implicit def powIntoImpl[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.inPlaceImpl2For[OpPow.type]("pow")

  implicit def addImplVS[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For_v_s[OpAdd.type]("add")
  implicit def subImplVS[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For_v_s[OpSub.type]("sub")
  implicit def mulImplVS[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For_v_s[OpMulScalar.type]("mul")
  implicit def mulMatrixImplVS[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For_v_s[OpMulMatrix.type]("mul")
  implicit def divImplVS[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For_v_s[OpDiv.type]("div")
  implicit def modImplVS[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For_v_s[OpMod.type]("mod")
  implicit def powImplVS[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For_v_s[OpPow.type]("pow")

  implicit def addImplSV[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For_s_v[OpAdd.type]("add")
  implicit def subImplSV[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For_s_v[OpSub.type]("sub")
  implicit def mulImplSV[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For_s_v[OpMulScalar.type]("mul")
  implicit def mulMatrixImplSV[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For_s_v[OpMulMatrix.type]("mul")
  implicit def divImplSV[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For_s_v[OpDiv.type]("div")
  implicit def modImplSV[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For_s_v[OpMod.type]("mod")
  implicit def powImplSV[T](implicit broker: CuMapKernels[CuMatrix[T], T]) =  broker.impl2For_s_v[OpPow.type]("pow")


  implicit def handhold0[T]: CanCollapseAxis.HandHold[CuMatrix[T], Axis._0.type, CuMatrix[T]] = null
  implicit def handhold1[T]: CanCollapseAxis.HandHold[CuMatrix[T], Axis._1.type, CuMatrix[T]] = null


  implicit def broadcastLHSColOpFromBinOp[Func, T](implicit op: UFunc.UImpl2[Func, CuMatrix[T], CuMatrix[T], CuMatrix[T]]):UFunc.UImpl2[Func, BroadcastedColumns[CuMatrix[T], CuMatrix[T]], CuMatrix[T], CuMatrix[T]] = {
    new UFunc.UImpl2[Func, BroadcastedColumns[CuMatrix[T], CuMatrix[T]], CuMatrix[T], CuMatrix[T]] {
      override def apply(vb: BroadcastedColumns[CuMatrix[T], CuMatrix[T]], v2: CuMatrix[T]) = {
        val v = vb.underlying
        require(v2.cols == 1)
        require(!v2.isTranspose)
        require(v.rows == v2.rows)
        import v.blas
        // trick: if the major stride is 0, then we iterate over the same column over and over again
        op(v, new CuMatrix(v.rows, v.cols, v2.data, v2.offset, 0, v2.isTranspose))
      }
    }
  }

  implicit def broadcastRHSColOpFromBinOp[Func, T](implicit op: UFunc.UImpl2[Func, CuMatrix[T], CuMatrix[T], CuMatrix[T]]):UFunc.UImpl2[Func, CuMatrix[T], BroadcastedColumns[CuMatrix[T], CuMatrix[T]], CuMatrix[T]] = {
    new UFunc.UImpl2[Func, CuMatrix[T], BroadcastedColumns[CuMatrix[T], CuMatrix[T]], CuMatrix[T]] {
      override def apply(v2: CuMatrix[T], vb: BroadcastedColumns[CuMatrix[T], CuMatrix[T]]) = {
        val v = vb.underlying
        require(v2.cols == 1)
        require(!v2.isTranspose)
        require(v.rows == v2.rows)
        import v.blas
        // trick: if the major stride is 0, then we iterate over the same column over and over again
        op(new CuMatrix(v.rows, v.cols, v2.data, v2.offset, 0, v2.isTranspose), v)
      }
    }
  }}
