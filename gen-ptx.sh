PATH="$PATH:/Developer/NVIDIA/CUDA-5.5/bin/"

if [ `uname` == Darwin ]; then
   NVCC_OPTS="-gencode arch=compute_30,code=sm_30 -ccbin /usr/bin/llvm-g++-4.2 $NVCC_OPTS"
fi

nvcc $NVCC_OPTS -D TYPE=float --ptx src/main/resources/gust/linalg/cuda/map_kernels_float.cu -o src/main/resources/gust/linalg/cuda/map_kernels_float.ptx
nvcc $NVCC_OPTS -D TYPE=double --ptx src/main/resources/gust/linalg/cuda/map_kernels_float.cu -o src/main/resources/gust/linalg/cuda/map_kernels_double.ptx

