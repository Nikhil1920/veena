import Foundation

enum GzipError: Error {
    case corrupt(String)
}

/// Streaming gzip decompression via libz, chunked to keep memory flat
/// while unpacking ~500 MB model archives.
enum Gzip {

    /// Decompresses `source` into `destination`, overwriting it.
    static func gunzip(source: URL, destination: URL) throws {
        let CHUNK = 256 * 1024
        var stream = z_stream()
        // windowBits 47 = auto-detect gzip/zlib headers
        let initResult = inflateInit2_(&stream, 47, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size))
        guard initResult == Z_OK else {
            throw GzipError.corrupt("inflateInit failed (\(initResult))")
        }
        defer { inflateEnd(&stream) }

        FileManager.default.createFile(atPath: destination.path, contents: nil)
        let input = try FileHandle(forReadingFrom: source)
        let output = try FileHandle(forWritingTo: destination)
        defer {
            try? input.close()
            try? output.close()
        }

        var inBuffer = [UInt8](repeating: 0, count: CHUNK)
        var outBuffer = [UInt8](repeating: 0, count: CHUNK)

        var sawInputEnd = false
        while true {
            if stream.avail_in == 0 && !sawInputEnd {
                let data = input.readData(ofLength: CHUNK)
                if data.isEmpty {
                    sawInputEnd = true
                } else {
                    data.copyBytes(to: &inBuffer, count: data.count)
                    stream.next_in = UnsafeMutablePointer<Bytef>(mutating: inBuffer)
                    stream.avail_in = UInt32(data.count)
                }
            }

            if stream.avail_in == 0 && sawInputEnd {
                break
            }

            stream.next_out = UnsafeMutablePointer<Bytef>(mutating: outBuffer)
            stream.avail_out = UInt32(CHUNK)

            let result = inflate(&stream, Z_NO_FLUSH)
            let produced = CHUNK - Int(stream.avail_out)
            if produced > 0 {
                output.write(Data(outBuffer[0..<produced]))
            }

            switch result {
            case Z_OK, Z_BUF_ERROR:
                continue
            case Z_STREAM_END:
                return
            default:
                throw GzipError.corrupt("inflate failed (\(result))")
            }
        }
        throw GzipError.corrupt("Truncated gzip stream")
    }
}
