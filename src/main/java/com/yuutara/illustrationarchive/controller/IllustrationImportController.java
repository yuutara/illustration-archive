package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.service.IllustrationImportResult;
import com.yuutara.illustrationarchive.service.IllustrationImportService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/illustrations")
public class IllustrationImportController {

	private final IllustrationImportService illustrationImportService;

	public IllustrationImportController(IllustrationImportService illustrationImportService) {
		this.illustrationImportService = illustrationImportService;
	}

	@PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public IllustrationImportResult importSingle(@RequestParam("file") MultipartFile file) {
		return illustrationImportService.importSingle(file);
	}
}
