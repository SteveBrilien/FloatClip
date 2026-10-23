using System;
using System.Globalization;

namespace FloatClip.Desktop.Services;
public static class FileSizeFormatter
{
    // Windows-style units: powers of 1024, with the familiar KB / MB / GB labels.
    // Storage, transfer limits and sorting still use the original exact byte counts.
    public static string Format(long bytes)
    {
        if(bytes<=0)return "0 KB";
        if(bytes<1024)return "< 1 KB";
        double value=bytes/1024d;string[] units={"KB","MB","GB","TB"};int unit=0;
        while(value>=1024&&unit<units.Length-1){value/=1024;unit++;}
        return value.ToString("0.#",CultureInfo.CurrentCulture)+" "+units[unit];
    }
}
