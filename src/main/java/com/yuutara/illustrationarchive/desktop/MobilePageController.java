package com.yuutara.illustrationarchive.desktop;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Static resources only get a directory index at the site root, so the phone entry gets the same pair as {@code /c/}. */
@Controller
@Profile(DesktopServer.PROFILE)
public class MobilePageController {

	@GetMapping("/m")
	public String redirectToDirectory() {
		return "redirect:/m/";
	}

	@GetMapping("/m/")
	public String page() {
		return "forward:/m/index.html";
	}
}
