package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.dto.TagCreateRequest;
import com.yuutara.illustrationarchive.dto.TagSummary;
import com.yuutara.illustrationarchive.service.TagService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tags")
public class TagController {

	private final TagService tagService;

	public TagController(TagService tagService) {
		this.tagService = tagService;
	}

	@PostMapping
	public ResponseEntity<TagSummary> create(@RequestBody TagCreateRequest request) {
		TagSummary tag = tagService.create(request.name());
		return ResponseEntity.status(HttpStatus.CREATED).body(tag);
	}

	@GetMapping
	public List<TagSummary> search(@RequestParam(required = false) String keyword) {
		return tagService.search(keyword);
	}
}
