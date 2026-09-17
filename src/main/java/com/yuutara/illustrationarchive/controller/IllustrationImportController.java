package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.service.IllustrationBatchImportResult;
import com.yuutara.illustrationarchive.service.IllustrationBatchImportService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/illustrations")
public class IllustrationImportController {

	private final IllustrationBatchImportService illustrationBatchImportService;

	public IllustrationImportController(IllustrationBatchImportService illustrationBatchImportService) {
		this.illustrationBatchImportService = illustrationBatchImportService;
	}

	@PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public IllustrationBatchImportResult importBatch(@RequestParam("files") List<MultipartFile> files) {
		return illustrationBatchImportService.importBatch(files);
	}
}
