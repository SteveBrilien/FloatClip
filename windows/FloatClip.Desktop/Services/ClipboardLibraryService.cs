namespace FloatClip.Desktop.Services;
public interface IClipboardLibraryService{int ClearUnprotected();}
public sealed class ClipboardLibraryService(ClipboardVault vault):IClipboardLibraryService
{
    public int ClearUnprotected(){int count=vault.ClearUnprotected();vault.PruneOrphanedImages();return count;}
}
