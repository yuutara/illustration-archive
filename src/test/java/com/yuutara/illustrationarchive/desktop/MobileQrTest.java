package com.yuutara.illustrationarchive.desktop;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobileQrTest {

	@Test
	void theSvgIsARealQrThatDecodesBackToThePairingUrl() throws Exception {
		String url = "http://192.168.1.23:47393/m/pair?code=ABCD2345";
		BitMatrix matrix = MobileQr.matrix(url);
		BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new MatrixLuminance(matrix)));
		Result decoded = new QRCodeReader().decode(bitmap);
		assertEquals(url, decoded.getText());
	}

	@Test
	void theDataUriCarriesAnSvgWithAQuietZone() {
		String uri = MobileQr.svgDataUri("http://192.168.1.23:47393/m/pair?code=ABCD2345");
		assertTrue(uri.startsWith("data:image/svg+xml;base64,"), uri);
		String svg = new String(Base64.getDecoder().decode(uri.substring("data:image/svg+xml;base64,".length())),
				StandardCharsets.UTF_8);
		assertTrue(svg.startsWith("<svg "), svg);
		assertTrue(svg.contains("fill=\"#fff\""), "the quiet zone must be white");
		assertTrue(svg.contains("shape-rendering=\"crispEdges\""), svg);
	}

	/** One matrix cell is one module, so decoding the same matrix checks what the phone camera will see. */
	private static final class MatrixLuminance extends LuminanceSource {

		private final BitMatrix matrix;

		private MatrixLuminance(BitMatrix matrix) {
			super(matrix.getWidth(), matrix.getHeight());
			this.matrix = matrix;
		}

		@Override
		public byte[] getRow(int y, byte[] row) {
			byte[] out = row == null || row.length < getWidth() ? new byte[getWidth()] : row;
			for (int x = 0; x < getWidth(); x++) {
				out[x] = matrix.get(x, y) ? 0 : (byte) 0xFF;
			}
			return out;
		}

		@Override
		public byte[] getMatrix() {
			byte[] pixels = new byte[getWidth() * getHeight()];
			for (int y = 0; y < getHeight(); y++) {
				for (int x = 0; x < getWidth(); x++) {
					pixels[y * getWidth() + x] = matrix.get(x, y) ? 0 : (byte) 0xFF;
				}
			}
			return pixels;
		}
	}
}
