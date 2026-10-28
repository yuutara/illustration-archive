package com.yuutara.illustrationarchive.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Entry for the C gallery preview under {@code /c/} (browse, search and filter, single-work editing). Static resources only get a directory index at the
 * site root, so {@code /c/} forwards to the page and {@code /c} redirects to {@code /c/}; the trailing slash keeps
 * the page's relative stylesheet and script paths under {@code /c/}. The existing gallery at {@code /} is unchanged.
 */
@Controller
public class CGalleryPageController {

	@GetMapping("/c")
	public String redirectToDirectory() {
		return "redirect:/c/";
	}

	@GetMapping("/c/")
	public String page() {
		return "forward:/c/index.html";
	}
}
