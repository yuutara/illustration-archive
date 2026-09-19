package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.dto.IllustrationDetail;
import com.yuutara.illustrationarchive.service.IllustrationDetailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/illustrations")
public class IllustrationDetailController {

	private final IllustrationDetailService illustrationDetailService;

	public IllustrationDetailController(IllustrationDetailService illustrationDetailService) {
		this.illustrationDetailService = illustrationDetailService;
	}

	@GetMapping("/{id}")
	public IllustrationDetail getDetail(@PathVariable long id) {
		return illustrationDetailService.getDetail(id);
	}
}
