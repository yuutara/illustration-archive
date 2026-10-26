package com.yuutara.illustrationarchive.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the pages which optional entry points to show. Served as a small synchronous script so the pages
 * know before their own scripts run, without an extra request round-trip or a visible flicker.
 * Both default to visible; the desktop PoC turns them off because X and AI are not available there.
 */
@RestController
public class UiCapabilitiesController {

	private final boolean xImport;
	private final boolean aiAnalysis;

	public UiCapabilitiesController(
			@Value("${illustration-archive.ui.x-import:true}") boolean xImport,
			@Value("${illustration-archive.ui.ai-analysis:true}") boolean aiAnalysis) {
		this.xImport = xImport;
		this.aiAnalysis = aiAnalysis;
	}

	@GetMapping(value = "/ui-capabilities.js", produces = "text/javascript")
	public ResponseEntity<String> script() {
		String body = "window.UiCapabilities = Object.freeze({ xImport: " + xImport
				+ ", aiAnalysis: " + aiAnalysis + " });\n";
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.contentType(MediaType.valueOf("text/javascript;charset=UTF-8"))
				.body(body);
	}
}
