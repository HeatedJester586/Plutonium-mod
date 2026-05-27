#include "backbone_shared.h"

SharedChunkData* g_sharedData = nullptr;

extern "C" {
    __declspec(dllexport) void InitializeBackbone(SharedChunkData* sharedDataPtr) {
        g_sharedData = sharedDataPtr;
    }
}