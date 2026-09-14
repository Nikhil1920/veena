import Foundation

/// Minimal tar reader for the ustar/GNU tarballs the model CDN produces:
/// plain files and directories only, 512-byte headers. GNU long-name ('L')
/// and pax ('x'/'g') metadata blocks are consumed and skipped.
enum TarGz {

    static func extract(tarFile: URL, into targetDir: URL) throws {
        try FileManager.default.createDirectory(at: targetDir, withIntermediateDirectories: true)
        let BLOCK = 512
        let handle = try FileHandle(forReadingFrom: tarFile)
        defer { try? handle.close() }

        var pendingLongName: String?

        while true {
            guard let headerData = try? handle.read(upToCount: BLOCK), headerData.count == BLOCK else {
                break
            }
            let header = [UInt8](headerData)
            if header[0] == 0 { break } // end-of-archive block

            var name = cString(header, 0, 100)
            let size = Int(parseTarNumber(header, 124, 12))
            let typeFlag = header[156]
            let prefix = cString(header, 345, 155)
            if !prefix.isEmpty { name = "\(prefix)/\(name)" }

            func skipContent() throws {
                let remaining = ((size + BLOCK - 1) / BLOCK) * BLOCK - size
                _ = try handle.read(upToCount: size)
                if remaining > 0 { _ = try handle.read(upToCount: remaining) }
            }

            switch typeFlag {
            case UInt8(ascii: "L"):
                let longData = try handle.read(upToCount: size) ?? Data()
                pendingLongName = String(data: longData, encoding: .utf8)?
                    .trimmingCharacters(in: CharacterSet(charactersIn: "\u{0}\n "))
                try skipPadding(handle, size)
                continue
            case UInt8(ascii: "x"), UInt8(ascii: "g"):
                try skipContent()
                continue
            default:
                break
            }

            if let longName = pendingLongName {
                name = longName
                pendingLongName = nil
            }

            guard let safeName = sanitize(name) else {
                try skipContent()
                continue
            }
            // Skip macOS AppleDouble/resource-fork junk shipped in some archives.
            let components = safeName.split(separator: "/").map(String.init)
            if components.contains(where: { $0.hasPrefix("._") || $0 == ".DS_Store" }) {
                try skipContent()
                continue
            }

            let outFile = targetDir.appendingPathComponent(safeName)

            switch typeFlag {
            case UInt8(ascii: "5"), UInt8(ascii: "/"):
                try FileManager.default.createDirectory(at: outFile, withIntermediateDirectories: true)
            case UInt8(ascii: "0"), 0:
                try FileManager.default.createDirectory(
                    at: outFile.deletingLastPathComponent(), withIntermediateDirectories: true)
                if let data = try handle.read(upToCount: size) {
                    try data.write(to: outFile, options: .atomic)
                }
                try skipPadding(handle, size)
            default:
                try skipContent() // symlinks/devices not expected
            }
        }
    }

    private static func skipPadding(_ handle: FileHandle, _ bytesRead: Int) throws {
        let rem = bytesRead % 512
        if rem != 0 { _ = try handle.read(upToCount: 512 - rem) }
    }

    private static func cString(_ bytes: [UInt8], _ offset: Int, _ length: Int) -> String {
        var end = offset + length
        for i in offset..<(offset + length) where bytes[i] == 0 {
            end = i
            break
        }
        let slice = bytes[offset..<end]
        return String(bytes: slice, encoding: .utf8)?
            .trimmingCharacters(in: CharacterSet(charactersIn: " \n")) ?? ""
    }

    private static func sanitize(_ name: String) -> String? {
        var cleaned = name.replacingOccurrences(of: "\\", with: "/")
        while cleaned.hasPrefix("/") { cleaned.removeFirst() }
        let parts = cleaned.split(separator: "/").filter { $0 != "." && $0 != ".." }
        if parts.isEmpty { return nil }
        return parts.joined(separator: "/")
    }

    private static func parseTarNumber(_ header: [UInt8], _ offset: Int, _ length: Int) -> Int64 {
        if header[offset] & 0x80 != 0 {
            // GNU base-256 encoding
            var value: Int64 = Int64(header[offset] & 0x7F)
            for i in 1..<length {
                value = (value << 8) | Int64(header[offset + i])
            }
            return value
        }
        let text = cString(header, offset, length).trimmingCharacters(in: .whitespaces)
        return Int64(text, radix: 8) ?? 0
    }
}
