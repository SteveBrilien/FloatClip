using System;
using System.Buffers.Binary;
using System.IO;
using System.Windows;
using System.Windows.Media.Imaging;
using System.Windows.Media;

namespace FloatClip.Desktop;

/** Prefer the registered PNG format, which preserves alpha; Bitmap is the DIB fallback. */
public static class ClipboardImages
{
    public const string PngFormat="PNG";
    public static BitmapSource Decode(byte[] bytes)
    {
        if(bytes.Length>ClipboardVault.MaximumImageBytes)throw new IOException("图片最大 8 MiB");
        if(bytes.Length<33||!bytes.AsSpan(0,8).SequenceEqual(new byte[]{137,80,78,71,13,10,26,10}))throw new IOException("图片格式不是 PNG");
        uint width=BinaryPrimitives.ReadUInt32BigEndian(bytes.AsSpan(16,4)),height=BinaryPrimitives.ReadUInt32BigEndian(bytes.AsSpan(20,4));
        if(width<1||height<1||width>16384||height>16384||(long)width*height>64000000)throw new IOException("图片尺寸超出安全范围");
        using var stream=new MemoryStream(bytes);
        var decoder=BitmapDecoder.Create(stream,BitmapCreateOptions.PreservePixelFormat,BitmapCacheOption.OnLoad);
        var frame=decoder.Frames[0];CheckSize(frame);frame.Freeze();return frame;
    }
    static void CheckSize(BitmapSource image)
    {
        if(image.PixelWidth<1||image.PixelHeight<1||image.PixelWidth>16384||image.PixelHeight>16384||(long)image.PixelWidth*image.PixelHeight>64000000)
            throw new IOException("图片尺寸超出安全范围");
    }
    public static byte[] Encode(BitmapSource image)
    {
        CheckSize(image);var encoder=new PngBitmapEncoder();encoder.Frames.Add(BitmapFrame.Create(image));
        using var output=new MemoryStream();encoder.Save(output);
        if(output.Length>ClipboardVault.MaximumImageBytes)throw new IOException("图片最大 8 MiB");
        return output.ToArray();
    }
    public static (byte[] Png,int Width,int Height)? Read(IDataObject? data)
    {
        if(data==null)return null;
        // Do not ask OLE to synthesize formats: use the app's PNG bytes when supplied.
        if(data.GetDataPresent(PngFormat,false))
        {
            byte[]? bytes=null;var value=data.GetData(PngFormat,false);
            if(value is byte[] array)bytes=array;
            else if(value is Stream input)
            {
                if(input.CanSeek)input.Position=0;
                using var output=new MemoryStream();var buffer=new byte[8192];int count;
                while((count=input.Read(buffer,0,buffer.Length))>0){if(output.Length+count>ClipboardVault.MaximumImageBytes)throw new IOException("图片最大 8 MiB");output.Write(buffer,0,count);}
                bytes=output.ToArray();
            }
            if(bytes!=null){var png=Decode(bytes);return (Encode(png),png.PixelWidth,png.PixelHeight);}
        }
        IOException? dibError=null;
        foreach(string format in new[]{DataFormats.GetDataFormat(17).Name,"DeviceIndependentBitmapV5",DataFormats.Dib})
            if(data.GetDataPresent(format,false)){
                var value=data.GetData(format,false);byte[]? dib=null;
                if(value is byte[] raw)dib=raw;
                else if(value is MemoryStream memory)dib=memory.ToArray();
                if(dib!=null)try{var image=DecodeDib(dib);return (Encode(image),image.PixelWidth,image.PixelHeight);}catch(IOException e){dibError=e;}
            }
        if(data.GetData(DataFormats.Bitmap,true) is BitmapSource bitmap)
            return (Encode(bitmap),bitmap.PixelWidth,bitmap.PixelHeight);
        if(dibError!=null)throw dibError;
        return null;
    }
    public static BitmapSource DecodeDib(byte[] dib)
    {
        if(dib.Length<40)throw new IOException("DIB 图片头不完整");
        int I(int offset)=>BinaryPrimitives.ReadInt32LittleEndian(dib.AsSpan(offset,4));
        int header=I(0),width=I(4),signedHeight=I(8);int bits=BinaryPrimitives.ReadUInt16LittleEndian(dib.AsSpan(14,2)),compression=I(16);
        if(header<40||header>dib.Length||BinaryPrimitives.ReadUInt16LittleEndian(dib.AsSpan(12,2))!=1||width<1||width>16384||signedHeight==int.MinValue||Math.Abs(signedHeight) is <1 or >16384||bits is not(24 or 32)||compression is not(0 or 3))throw new IOException("DIB 图片格式不受支持");
        int height=Math.Abs(signedHeight);if((long)width*height>64000000)throw new IOException("图片尺寸超出安全范围");
        int offset=header;
        if(compression==3){if(header==40)offset+=12;if(dib.Length<52||I(40)!=0xff0000||I(44)!=0xff00||I(48)!=0xff)throw new IOException("DIB 颜色格式不受支持");}
        int colors=I(32);if(colors<0||colors>256)throw new IOException("DIB 调色板无效");offset+=colors*4;
        int stride=checked((width*bits+31)/32*4);if((long)offset+(long)stride*height>dib.Length)throw new IOException("DIB 图片数据不完整");
        bool alpha=bits==32&&header>=108&&I(52)==unchecked((int)0xff000000);
        byte[] pixels=new byte[checked(width*height*4)];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++){
            int input=offset+(signedHeight<0?y:height-y-1)*stride+x*(bits/8),output=(y*width+x)*4;
            pixels[output]=dib[input];pixels[output+1]=dib[input+1];pixels[output+2]=dib[input+2];pixels[output+3]=alpha?dib[input+3]:(byte)255;
        }
        var result=BitmapSource.Create(width,height,96,96,PixelFormats.Bgra32,null,pixels,width*4);result.Freeze();return result;
    }
}
