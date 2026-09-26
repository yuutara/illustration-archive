package com.yuutara.illustrationarchive.service;

public class FullResolutionMismatchException extends XPhotoDownloadException {
	public FullResolutionMismatchException(String mediaKey, int expectedWidth, int expectedHeight,
			int actualWidth, int actualHeight) {
		super("X photo " + mediaKey + " is not full resolution: expected "
				+ expectedWidth + "x" + expectedHeight + ", got " + actualWidth + "x" + actualHeight + ".");
	}
}
