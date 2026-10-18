using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;

namespace FloatClip.NearbyProbe
{
    // P0 accepts generated probes, never real clipboard data. No insecure fallback.
    public static class Probe
    {
        public static readonly Guid Service = new Guid("db7aa0e6-0ccd-47d0-b749-80f17b9bfc21");
        public const int MaxPayload = 16384;
        public sealed class Frame
        {
            public readonly byte Kind;
            public readonly Guid Id;
            private readonly byte[] data;
            public byte[] Payload { get { return (byte[])data.Clone(); } }
            public Frame(byte kind, Guid id, byte[] payload)
            {
                if ((kind != 1 && kind != 2) || payload == null || payload.Length > MaxPayload ||
                    (kind == 2 && payload.Length != 32)) throw new ArgumentException("Invalid frame");
                Kind = kind; Id = id; data = (byte[])payload.Clone();
            }
        }

        public static byte[] TestPayload(Guid id)
        {
            return Encoding.UTF8.GetBytes("FloatClip-P0\n  Unicode: \u4e2d\u6587 / emoji \ud83d\udccb\n\tindent\r\n" + id.ToString() + "  \n");
        }
        public static byte[] Digest(byte[] data) { using (SHA256 h = SHA256.Create()) return h.ComputeHash(data); }
        private static bool Same(byte[] a, byte[] b)
        {
            if (a.Length != b.Length) return false;
            int diff = 0; for (int i = 0; i < a.Length; i++) diff |= a[i] ^ b[i]; return diff == 0;
        }
        public static Frame Ack(Frame probe)
        {
            if (probe.Kind != 1 || !Same(probe.Payload, TestPayload(probe.Id))) throw new IOException("Only generated P0 payloads accepted");
            return new Frame(2, probe.Id, Digest(probe.Payload));
        }
        public static bool Matches(Frame ack, Frame sent)
        {
            return ack.Kind == 2 && sent.Kind == 1 && ack.Id == sent.Id && Same(ack.Payload, Digest(sent.Payload));
        }
        public static void Write(Stream stream, Frame frame)
        {
            byte[] payload = frame.Payload;
            byte[] header = new byte[58];
            header[0] = 0x46; header[1] = 0x43; header[2] = 0x50; header[3] = 0x30;
            header[4] = 1; header[5] = frame.Kind;
            string id = frame.Id.ToString("N");
            for (int i = 0; i < 16; i++) header[6 + i] = Convert.ToByte(id.Substring(i * 2, 2), 16);
            int n = payload.Length;
            header[22] = (byte)(n >> 24); header[23] = (byte)(n >> 16); header[24] = (byte)(n >> 8); header[25] = (byte)n;
            Buffer.BlockCopy(Digest(payload), 0, header, 26, 32);
            stream.Write(header, 0, header.Length); stream.Write(payload, 0, payload.Length); stream.Flush();
        }
        private static void Exact(Stream stream, byte[] buffer, int offset, int count)
        {
            while (count > 0)
            {
                int n = stream.Read(buffer, offset, count);
                if (n == 0) throw new EndOfStreamException("Partial frame");
                offset += n; count -= n;
            }
        }
        public static Frame Read(Stream stream)
        {
            int first = stream.ReadByte(); if (first < 0) return null;
            byte[] header = new byte[58]; header[0] = (byte)first; Exact(stream, header, 1, 57);
            if (header[0] != 0x46 || header[1] != 0x43 || header[2] != 0x50 || header[3] != 0x30) throw new IOException("Wrong magic");
            if (header[4] != 1 || (header[5] != 1 && header[5] != 2)) throw new IOException("Unsupported version/kind");
            int length = (header[22] << 24) | (header[23] << 16) | (header[24] << 8) | header[25];
            if (length < 0 || length > MaxPayload || (header[5] == 2 && length != 32)) throw new IOException("Invalid length");
            Guid id = Guid.ParseExact(BitConverter.ToString(header, 6, 16).Replace("-", ""), "N");
            byte[] payload = new byte[length]; Exact(stream, payload, 0, length);
            byte[] expected = new byte[32]; Buffer.BlockCopy(header, 26, expected, 0, 32);
            if (!Same(expected, Digest(payload))) throw new IOException("Digest mismatch");
            return new Frame(header[5], id, payload);
        }

        public static int Run(string peerAddress, int count)
        {
            if (Environment.OSVersion.Platform != PlatformID.Win32NT) throw new PlatformNotSupportedException("Bluetooth transport requires Windows");
            if (count < 1 || count > 50) throw new ArgumentOutOfRangeException("count");
            string clean = peerAddress.Replace(":", "").Replace("-", "");
            if (clean.Length != 12) throw new ArgumentException("Expected 12 hexadecimal Bluetooth address digits");
            ulong address = Convert.ToUInt64(clean, 16);
            if (address == 0 || address == 0xffffffffffffUL) throw new ArgumentException("Invalid target address");
            using (NativeBluetooth stream = new NativeBluetooth(address))
            {
                Console.WriteLine("Authenticated/encrypted RFCOMM connected. Generated probes only.");
                Dictionary<Guid, Frame> outstanding = new Dictionary<Guid, Frame>();
                Dictionary<Guid, long> starts = new Dictionary<Guid, long>();
                Guid firstId = Guid.NewGuid();
                Frame initial = new Frame(1, firstId, TestPayload(firstId));
                outstanding[initial.Id] = initial; starts[initial.Id] = Stopwatch.GetTimestamp(); Write(stream, initial);
                int sent = 1, confirmed = 0, received = 0, frames = 0;
                HashSet<Guid> seen = new HashSet<Guid>();
                Stopwatch deadline = Stopwatch.StartNew();
                while (confirmed < count || received < 10)
                {
                    if (deadline.Elapsed > TimeSpan.FromSeconds(30) || ++frames > 256) throw new IOException("Probe session exceeded limits");
                    Frame frame = Read(stream); if (frame == null) throw new IOException("Peer disconnected before test completion");
                    if (frame.Kind == 1)
                    {
                        Write(stream, Ack(frame));
                        if (seen.Add(frame.Id)) received++;
                        Console.WriteLine("RX verified: " + received + " (" + frame.Payload.Length + " bytes)");
                    }
                    else
                    {
                        Frame original;
                        if (!outstanding.TryGetValue(frame.Id, out original) || !Matches(frame, original)) throw new IOException("Unexpected/mismatched ACK");
                        double ms = (Stopwatch.GetTimestamp() - starts[frame.Id]) * 1000.0 / Stopwatch.Frequency;
                        outstanding.Remove(frame.Id); starts.Remove(frame.Id); confirmed++;
                        Console.WriteLine("TX confirmed: " + confirmed + "; round trip " + ms.ToString("F1", System.Globalization.CultureInfo.InvariantCulture) + " ms");
                        if (sent < count)
                        {
                            Guid id = Guid.NewGuid(); Frame next = new Frame(1, id, TestPayload(id));
                            outstanding[id] = next; starts[id] = Stopwatch.GetTimestamp(); Write(stream, next); sent++;
                        }
                    }
                }
                Console.WriteLine("PASS: " + confirmed + " outgoing and " + received + " incoming probes; no clipboard accessed.");
            }
            return 0;
        }

        // ws2bth.h explicitly packs SOCKADDR_BTH to 1 byte (30 bytes, btAddr at offset 2).
        [StructLayout(LayoutKind.Sequential, Pack = 1)]
        private struct BthAddress { public ushort Family; public ulong Address; public Guid Service; public uint Port; }
        private sealed class NativeBluetooth : Stream
        {
            private IntPtr handle = new IntPtr(-1);
            private bool started;
            [DllImport("ws2_32.dll")] private static extern int WSAStartup(ushort version, IntPtr data);
            [DllImport("ws2_32.dll")] private static extern int WSACleanup();
            [DllImport("ws2_32.dll")] private static extern int WSAGetLastError();
            [DllImport("ws2_32.dll")] private static extern IntPtr socket(int family, int type, int protocol);
            [DllImport("ws2_32.dll")] private static extern int connect(IntPtr s, ref BthAddress address, int size);
            [DllImport("ws2_32.dll")] private static extern int setsockopt(IntPtr s, int level, int name, ref int value, int length);
            [DllImport("ws2_32.dll")] private static extern int recv(IntPtr s, byte[] buffer, int length, int flags);
            [DllImport("ws2_32.dll")] private static extern int send(IntPtr s, byte[] buffer, int length, int flags);
            [DllImport("ws2_32.dll")] private static extern int closesocket(IntPtr s);
            private static IOException Error(string action) { return new IOException(action + " failed, Winsock " + WSAGetLastError()); }
            private void Option(int level, int option, int value, string name)
            {
                if (setsockopt(handle, level, option, ref value, 4) != 0) throw Error("setsockopt(" + name + ")");
            }
            public NativeBluetooth(ulong address)
            {
                IntPtr data = Marshal.AllocHGlobal(512);
                try { int rc = WSAStartup(0x0202, data); if (rc != 0) throw new IOException("WSAStartup failed: " + rc); started = true; }
                finally { Marshal.FreeHGlobal(data); }
                try
                {
                    handle = socket(32, 1, 3); if (handle == new IntPtr(-1)) throw Error("socket");
                    Option(3, unchecked((int)0x80000001), 1, "SO_BTH_AUTHENTICATE"); // SO_BTH_AUTHENTICATE
                    Option(3, 2, 1, "SO_BTH_ENCRYPT");                         // SO_BTH_ENCRYPT
                    Option(0xffff, 0x1005, 10000, "SO_SNDTIMEO");           // SO_SNDTIMEO
                    Option(0xffff, 0x1006, 10000, "SO_RCVTIMEO");           // SO_RCVTIMEO
                    BthAddress target = new BthAddress { Family = 32, Address = address, Service = Service, Port = 0 };
                    using (Timer timer = new Timer(delegate(object state) { CloseSocket(); }, null, 15000, Timeout.Infinite))
                    {
                        if (connect(handle, ref target, Marshal.SizeOf(typeof(BthAddress))) != 0) throw Error("connect");
                    }
                }
                catch { Dispose(); throw; }
            }
            private void CloseSocket()
            {
                IntPtr old = Interlocked.Exchange(ref handle, new IntPtr(-1));
                if (old != new IntPtr(-1)) closesocket(old);
            }
            protected override void Dispose(bool disposing)
            {
                CloseSocket(); if (started) { started = false; WSACleanup(); } base.Dispose(disposing);
            }
            public override int Read(byte[] buffer, int offset, int count)
            {
                if (count == 0) return 0;
                byte[] chunk = new byte[count]; int n = recv(handle, chunk, count, 0);
                if (n < 0) throw Error("recv"); Buffer.BlockCopy(chunk, 0, buffer, offset, n); return n;
            }
            public override void Write(byte[] buffer, int offset, int count)
            {
                while (count > 0)
                {
                    byte[] chunk = new byte[count]; Buffer.BlockCopy(buffer, offset, chunk, 0, count);
                    int n = send(handle, chunk, count, 0); if (n <= 0) throw Error("send"); offset += n; count -= n;
                }
            }
            public override void Flush() { }
            public override bool CanRead { get { return handle != new IntPtr(-1); } }
            public override bool CanWrite { get { return CanRead; } }
            public override bool CanSeek { get { return false; } }
            public override long Length { get { throw new NotSupportedException(); } }
            public override long Position { get { throw new NotSupportedException(); } set { throw new NotSupportedException(); } }
            public override long Seek(long offset, SeekOrigin origin) { throw new NotSupportedException(); }
            public override void SetLength(long value) { throw new NotSupportedException(); }
        }

        public static void SelfTest(string javaVectors)
        {
            if (Marshal.SizeOf(typeof(BthAddress)) != 30 || Marshal.OffsetOf(typeof(BthAddress), "Address").ToInt32() != 2) throw new Exception("Wrong Bluetooth native layout");
            Guid id = new Guid("00112233-4455-6677-8899-aabbccddeeff");
            Frame probe = new Frame(1, id, TestPayload(id)); byte[] encoded;
            using (MemoryStream s = new MemoryStream()) { Write(s, probe); encoded = s.ToArray(); }
            using (MemoryStream s = new MemoryStream(encoded)) { if (!Matches(Ack(Read(s)), probe)) throw new Exception("Roundtrip failed"); }
            for (int i = 1; i < encoded.Length; i++)
            {
                bool rejected = false;
                try { using (MemoryStream s = new MemoryStream(encoded, 0, i)) { Read(s); } }
                catch (IOException) { rejected = true; }
                if (!rejected) throw new Exception("Accepted partial frame");
            }
            byte[] corrupt = (byte[])encoded.Clone(); corrupt[corrupt.Length - 1] ^= 1;
            try { using (MemoryStream s = new MemoryStream(corrupt)) { Read(s); } throw new Exception("Accepted corrupt data"); }
            catch (IOException) { }
            if (!String.IsNullOrEmpty(javaVectors))
            {
                if (!Same(encoded, File.ReadAllBytes(Path.Combine(javaVectors, "probe.bin")))) throw new Exception("Java/C# probe bytes differ");
                using (MemoryStream s = new MemoryStream())
                {
                    Write(s, Ack(probe));
                    if (!Same(s.ToArray(), File.ReadAllBytes(Path.Combine(javaVectors, "ack.bin")))) throw new Exception("Java/C# ACK bytes differ");
                }
            }
            Console.WriteLine("PASS: C# framing, truncation, integrity, native layout" + (String.IsNullOrEmpty(javaVectors) ? "" : ", Java byte-for-byte interoperability") + "; no radio hardware exercised.");
        }

        private static void ReportFailure(Exception error)
        {
            Console.Error.WriteLine(error.ToString());
            try
            {
                string directory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                    "FloatClip", "NearbyProbe", "Logs");
                Directory.CreateDirectory(directory);
                string path = Path.Combine(directory, "error-" + DateTime.UtcNow.ToString("yyyyMMddTHHmmssfff") +
                    "-" + Guid.NewGuid().ToString("N").Substring(0, 8) + ".log");
                File.WriteAllText(path, "UTC: " + DateTime.UtcNow.ToString("O") + Environment.NewLine +
                    "OS: " + Environment.OSVersion + Environment.NewLine +
                    "Process bits: " + (IntPtr.Size * 8) + Environment.NewLine +
                    "Runtime: " + Environment.Version + Environment.NewLine + error, new UTF8Encoding(false));
                Console.Error.WriteLine("Error log: " + path);
            }
            catch (Exception logError) { Console.Error.WriteLine("Could not save error log: " + logError.Message); }
        }

        private static int Usage()
        {
            Console.WriteLine("FloatClip Bluetooth P0 - generated test data only.");
            Console.WriteLine("Double-click to open the interactive console, or use:");
            Console.WriteLine("  --interactive");
            Console.WriteLine("  --self-test [Java vector directory]");
            Console.WriteLine("  --connect AA:BB:CC:DD:EE:FF [count]");
            return 0;
        }

        private static int Interactive()
        {
            if (!Console.IsOutputRedirected)
            {
                try { Console.OutputEncoding = new UTF8Encoding(false); }
                catch (IOException) { }
            }
            Console.WriteLine("FloatClip 蓝牙连接测试 P0");
            Console.WriteLine("这版是控制台测试工具。窗口会保持打开，只收发生成的测试文字。");
            Console.WriteLine("先在系统蓝牙设置完成配对；手机打开测试 App，选择此电脑并点击等待连接。");
            Console.WriteLine("手机地址可在另一个 PowerShell 窗口运行 .\\Start-NearbyProbe.ps1 -List 查看。");
            Console.WriteLine("输入 test 运行本地自检，输入手机蓝牙地址开始连接，输入 q 退出。");
            while (true)
            {
                Console.Write("\n手机地址 / test / q > ");
                string input = Console.ReadLine();
                if (input == null || String.Equals(input.Trim(), "q", StringComparison.OrdinalIgnoreCase)) return 0;
                input = input.Trim();
                if (input.Length == 0) continue;
                try
                {
                    if (String.Equals(input, "test", StringComparison.OrdinalIgnoreCase)) SelfTest(null);
                    else Run(input, 10);
                }
                catch (Exception error) { ReportFailure(error); }
            }
        }

        public static int Main(string[] args)
        {
            try
            {
                if (args.Length > 0 && args[0] == "--self-test") { SelfTest(args.Length > 1 ? args[1] : null); return 0; }
                if (args.Length == 0) return Console.IsInputRedirected ? Usage() : Interactive();
                if (args.Length == 1 && args[0] == "--interactive") return Interactive();
                if (args.Length == 1 && args[0] == "--help") return Usage();
                if (args.Length >= 2 && args[0] == "--connect") return Run(args[1], args.Length >= 3 ? Int32.Parse(args[2]) : 10);
                throw new ArgumentException("Unknown command");
            }
            catch (Exception e) { ReportFailure(e); return 1; }
        }
    }
}
