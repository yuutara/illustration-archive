package com.yuutara.illustrationarchive;

import com.yuutara.illustrationarchive.desktop.DesktopLauncher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IllustrationArchiveApplication {

	public static void main(String[] args) {
		// The jpackage desktop PoC sets this flag; the normal MySQL server start is unchanged.
		if (Boolean.getBoolean("illustration-archive.desktop")) {
			DesktopLauncher.main(args);
			return;
		}
		SpringApplication.run(IllustrationArchiveApplication.class, args);
	}

}
