package com.yuutara.illustrationarchive.storage;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.Set;

/** Container identification only, not a decoder. Walk top-level ISO BMFF boxes by their sizes. */
final class Mp4Validation {
	private static final Set<Integer> BRANDS = Set.of(fourcc("isom"), fourcc("iso2"), fourcc("iso3"),
			fourcc("iso4"), fourcc("iso5"), fourcc("iso6"), fourcc("iso7"), fourcc("iso8"),
			fourcc("iso9"), fourcc("mp41"), fourcc("mp42"), fourcc("avc1"), fourcc("M4V "), fourcc("dash"));
	private Mp4Validation() { }

	static void validate(Path path) throws IOException {
		try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
			long length = file.length();
			long position = 0;
			while (length - position >= 8) {
				file.seek(position);
				long size = Integer.toUnsignedLong(file.readInt());
				int type = file.readInt();
				int header = 8;
				if (size == 1) {
					if (length - position < 16) break;
					size = file.readLong();
					header = 16;
				} else if (size == 0) size = length - position;
				if (size < header || size > length - position) break;
				if (type == fourcc("ftyp")) {
					long payload = size - header;
					if (payload < 8 || payload % 4 != 0) break;
					boolean mp4 = BRANDS.contains(file.readInt());
					file.readInt(); // minor version, not a brand
					for (long remaining = payload - 8; remaining > 0; remaining -= 4) {
						mp4 |= BRANDS.contains(file.readInt());
					}
					if (mp4) return;
					break;
				}
				position += size;
			}
		}
		throw new FileStorageValidationException("Uploaded file has no valid MP4 ftyp box.");
	}

	private static int fourcc(String value) {
		return value.charAt(0) << 24 | value.charAt(1) << 16 | value.charAt(2) << 8 | value.charAt(3);
	}
}
