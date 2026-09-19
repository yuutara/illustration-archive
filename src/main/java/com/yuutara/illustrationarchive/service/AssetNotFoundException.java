package com.yuutara.illustrationarchive.service;

public class AssetNotFoundException extends RuntimeException {

	public AssetNotFoundException(long assetId) {
		super("Asset not found: " + assetId);
	}
}
