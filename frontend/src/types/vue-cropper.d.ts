declare module 'vue-cropper' {
  import type { DefineComponent, Plugin } from 'vue'

  export interface CropperInstance {
    changeScale(scale: number): void
    rotateLeft(): void
    rotateRight(): void
    getCropBlob(callback: (blob: Blob) => void): void
  }

  export const VueCropper: DefineComponent
  const plugin: Plugin
  export default plugin
}
