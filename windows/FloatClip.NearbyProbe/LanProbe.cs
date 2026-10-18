using System;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography.X509Certificates;

namespace FloatClip.NearbyProbe
{
    public static class LanProbe
    {
        private static byte[] Hex(string value)
        {
            if (value == null || value.Length != 64) throw new ArgumentException("Expected 64 hexadecimal digits");
            byte[] bytes = new byte[32];
            for (int i = 0; i < bytes.Length; i++) bytes[i] = Convert.ToByte(value.Substring(i * 2, 2), 16);
            return bytes;
        }
        private static bool Same(byte[] a, byte[] b)
        {
            if (a.Length != b.Length) return false;
            int difference = 0;
            for (int i = 0; i < a.Length; i++) difference |= a[i] ^ b[i];
            return difference == 0;
        }
        public static int Run(string host, int port, string certificatePin, string invitation)
        {
            IPAddress address;
            if (!IPAddress.TryParse(host, out address) || address.AddressFamily != AddressFamily.InterNetwork)
                throw new ArgumentException("Use the phone's private IPv4 address");
            byte[] ip = address.GetAddressBytes();
            if (!(ip[0] == 10 || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31) || (ip[0] == 192 && ip[1] == 168)))
                throw new ArgumentException("P0 LAN accepts private network addresses only");
            if (port < 1024 || port > 65535) throw new ArgumentOutOfRangeException("port");
            byte[] pin = Hex(certificatePin), secret = Hex(invitation);
            using (TcpClient client = new TcpClient(AddressFamily.InterNetwork))
            {
                IAsyncResult connect = client.BeginConnect(address, port, null, null);
                using (System.Threading.WaitHandle wait = connect.AsyncWaitHandle)
                {
                    if (!wait.WaitOne(15000)) throw new IOException("LAN connection timed out");
                    client.EndConnect(connect);
                }
                client.NoDelay = true;
                using (SslStream stream = new SslStream(client.GetStream(), false,
                    delegate(object sender, X509Certificate cert, X509Chain chain, SslPolicyErrors errors)
                    {
                        return cert != null && Same(Probe.Digest(cert.GetRawCertData()), pin);
                    }))
                using (System.Threading.Timer deadline = new System.Threading.Timer(
                    delegate(object state) { client.Close(); }, null, 30000, System.Threading.Timeout.Infinite))
                {
                    stream.ReadTimeout = 10000;
                    stream.WriteTimeout = 10000;
                    stream.AuthenticateAsClient("FloatClip LAN P0", null, SslProtocols.Tls12, false);
                    stream.Write(new byte[] { 0x46, 0x43, 0x4c, 0x31 }, 0, 4);
                    stream.Write(secret, 0, secret.Length);
                    stream.Flush();
                    Array.Clear(secret, 0, secret.Length);
                    if (stream.ReadByte() != 1) throw new IOException("LAN invitation rejected");
                    Console.WriteLine("LAN: pinned TLS 1.2 + one-use invitation authenticated.");
                    for (int i = 1; i <= 10; i++)
                    {
                        Probe.Frame incoming = Probe.Read(stream);
                        if (incoming == null) throw new IOException("LAN peer disconnected");
                        Probe.Write(stream, Probe.Ack(incoming));
                        Guid id = Guid.NewGuid();
                        Probe.Frame outgoing = new Probe.Frame(1, id, Probe.TestPayload(id));
                        long began = Stopwatch.GetTimestamp();
                        Probe.Write(stream, outgoing);
                        Probe.Frame ack = Probe.Read(stream);
                        if (ack == null || !Probe.Matches(ack, outgoing)) throw new IOException("LAN ACK mismatch");
                        double ms = (Stopwatch.GetTimestamp() - began) * 1000.0 / Stopwatch.Frequency;
                        Console.WriteLine("LAN TX/RX " + i + "/10 verified; RTT " + ms.ToString("F1", System.Globalization.CultureInfo.InvariantCulture) + " ms");
                    }
                    Console.WriteLine("PASS LAN: 10 outgoing + 10 incoming generated probes; no clipboard accessed.");
                }
            }
            return 0;
        }
    }
}
