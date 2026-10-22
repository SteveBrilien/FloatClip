using System.IO;
using System.Windows;
using System.Windows.Media.Imaging;

namespace FloatClip.Desktop;

/** Mark our copy event so returning remote text to the system clipboard keeps its provenance. */
static class ClipboardWrites
{
    const string OwnFormat="FloatClip.ClipboardWrite.v1";
    public static bool IsOwnWrite()=>Clipboard.ContainsData(OwnFormat);
    public static void Write(string value)
    {
        var data=new DataObject();
        data.SetText(value,TextDataFormat.UnicodeText);
        data.SetData(OwnFormat,"1");
        Clipboard.SetDataObject(data,true);
    }
    public static void WriteImage(byte[] pngBytes)
    {
        var image=ClipboardImages.Decode(pngBytes);
        using var png=new MemoryStream(pngBytes);
        var data=new DataObject();data.SetImage(image);data.SetData(ClipboardImages.PngFormat,png,false);
        data.SetData(OwnFormat,"1");Clipboard.SetDataObject(data,true);
    }
}
