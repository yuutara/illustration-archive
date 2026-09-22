package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.service.IllustrationBatchImportResult;
import com.yuutara.illustrationarchive.service.IllustrationBatchImportService;
import com.yuutara.illustrationarchive.service.DuplicateIllustrationException;
import com.yuutara.illustrationarchive.service.IllustrationDuplicateResult;
import com.yuutara.illustrationarchive.service.IllustrationImportResult;
import com.yuutara.illustrationarchive.service.IllustrationImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
	private final IllustrationImportService illustrationImportService;

	@Autowired
	public IllustrationImportController(
			IllustrationBatchImportService illustrationBatchImportService,
			IllustrationImportService illustrationImportService
	) {
		this.illustrationBatchImportService = illustrationBatchImportService;
		this.illustrationImportService = illustrationImportService;
	}

	@PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public IllustrationBatchImportResult importBatch(@RequestParam("files") List<MultipartFile> files) {
		return illustrationBatchImportService.importBatch(files);
	}

	@PostMapping(value = "/import/single", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public IllustrationImportResult importSingle(@RequestParam("file") MultipartFile file) {
		return illustrationImportService.importSingle(file);
	}

	@ExceptionHandler(DuplicateIllustrationException.class)
	public ResponseEntity<IllustrationDuplicateResult> handleDuplicate(DuplicateIllustrationException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new IllustrationDuplicateResult(
						DuplicateIllustrationException.ERROR_CODE,
						exception.illustrationId()
				));
	}
}
