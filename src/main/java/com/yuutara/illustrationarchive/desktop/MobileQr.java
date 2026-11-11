package com.yuutara.illustrationarchive.desktop;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/** A pairing QR rendered as an SVG data URI; it is shown only in the local Settings page. */
final class MobileQr {

	private MobileQr() {
	}

	/**
	 * One matrix cell is one QR module, quiet zone included, so the SVG stays sharp at any size and the test can decode
	 * the same matrix without a raster step.
	 */
	static BitMatrix matrix(String content) {
		try {
			return new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 256, 256, Map.of(
					EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
					EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name(),
					EncodeHintType.MARGIN, 4));
		} catch (WriterException failed) {
			throw new IllegalArgumentException("无法生成配对二维码", failed);
		}
	}

	static String svgDataUri(String content) {
		BitMatrix matrix = matrix(content);
		int size = matrix.getWidth();
		StringBuilder path = new StringBuilder(size * size / 8);
		// Horizontal runs keep the SVG small while every black module stays exactly one unit wide / tall.
		for (int y = 0; y < size; y++) {
			int x = 0;
			while (x < size) {
				if (!matrix.get(x, y)) {
					x++;
					continue;
				}
				int start = x;
				while (x < size && matrix.get(x, y)) {
					x++;
				}
				int length = x - start;
				path.append('M').append(start).append(' ').append(y).append('h').append(length)
						.append("v1h-").append(length).append('z');
			}
		}
		String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + size + " " + size
				+ "\" shape-rendering=\"crispEdges\"><rect width=\"" + size + "\" height=\"" + size
				+ "\" fill=\"#fff\"/><path d=\"" + path + "\" fill=\"#111\"/></svg>";
		return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
	}
}
