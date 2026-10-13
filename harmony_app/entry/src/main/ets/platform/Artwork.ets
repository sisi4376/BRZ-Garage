import { common } from '@kit.AbilityKit';
import { componentSnapshot } from '@kit.ArkUI';
import { image } from '@kit.ImageKit';
import { picker } from '@kit.CoreFileKit';
import { writeFile } from './ArchiveRuntime';
export async function exportPlate(context: common.UIAbilityContext): Promise<boolean> {
  const pixels = await componentSnapshot.get('plate-preview');
  const packer = image.createImagePacker();
  try {
    const png = await packer.packing(pixels, { format: 'image/png', quality: 100 });
    const selected = await new picker.DocumentViewPicker(context).save({ newFileNames: ['BRZ-Plate.png'] });
    if (!selected.length) return false; writeFile(selected[0], new Uint8Array(png)); return true;
  } finally { await packer.release(); await pixels.release(); }
}
