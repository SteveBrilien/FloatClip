using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace FloatClip.Share;
internal static class UpdateManifestVerifier
{
    static byte[] Decode(string value)
    {
        if(value.Length>100000)throw new IOException("更新清单过大");
        string normalized=value.Replace('-','+').Replace('_','/');return Convert.FromBase64String(normalized.PadRight((normalized.Length+3)/4*4,'='));
    }
    internal static byte[] Verify(byte[] data,string channel="share-preview",string? publicKey=null,long? now=null)
    {
        try
        {
            using var document=JsonDocument.Parse(data);
            string token=document.RootElement.GetProperty("nativeUpdates").GetProperty(channel).GetString()??throw new IOException("更新清单签名缺失");
            var parts=token.Split('.');if(parts.Length!=3)throw new IOException("更新清单签名格式无效");
            using var header=JsonDocument.Parse(Decode(parts[0]));
            if(header.RootElement.TryGetProperty("crit",out _)||header.RootElement.TryGetProperty("b64",out _))throw new IOException("更新清单使用了不受支持的签名扩展");
            if(header.RootElement.GetProperty("alg").GetString()!="RS256"||header.RootElement.GetProperty("kid").GetString()!=UpdateTrust.KeyId||header.RootElement.GetProperty("typ").GetString()!="JOSE")throw new IOException("更新清单签名算法或密钥不受支持");
            using var key=RSA.Create();key.ImportFromPem(publicKey??UpdateTrust.PublicKey);
            if(!key.VerifyData(Encoding.ASCII.GetBytes(parts[0]+"."+parts[1]),Decode(parts[2]),HashAlgorithmName.SHA256,RSASignaturePadding.Pkcs1))throw new IOException("更新清单签名校验失败");
            using var payload=JsonDocument.Parse(Decode(parts[1]));var root=payload.RootElement;long timestamp=now??DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            long issued=root.GetProperty("issuedAt").GetInt64(),expires=root.GetProperty("expiresAt").GetInt64();
            if(root.GetProperty("schema").GetInt32()!=1||expires<=timestamp||issued>timestamp+900||expires<=issued)throw new IOException("更新清单已过期或时间无效");
            var release=root.GetProperty("release");if(release.GetProperty("channel").GetString()!=channel)throw new IOException("更新通道不匹配");
            return Encoding.UTF8.GetBytes(release.GetRawText());
        }
        catch(Exception error)when(error is JsonException or InvalidOperationException or FormatException or System.Collections.Generic.KeyNotFoundException or CryptographicException){throw new IOException("更新清单签名或结构无效",error);}
    }
}
