package com.firedoge.kineticassembly.minecraft.assembly;

record SavedAssemblyPointer(short storageIndex, short assemblyIndex) {
    int packed() {
        return (storageIndex << 16) | (assemblyIndex & 0xFFFF);
    }

    static SavedAssemblyPointer unpack(int packed) {
        short storageIndex = (short) (packed >> 16);
        short assemblyIndex = (short) (packed & 0xFFFF);
        return new SavedAssemblyPointer(storageIndex, assemblyIndex);
    }
}
