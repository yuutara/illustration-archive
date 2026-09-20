package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.dto.AuthorCreateRequest;
import com.yuutara.illustrationarchive.dto.AuthorDetail;
import com.yuutara.illustrationarchive.dto.AuthorSummary;
import com.yuutara.illustrationarchive.service.AuthorService;
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
@RequestMapping("/api/authors")
public class AuthorController {

	private final AuthorService authorService;

	public AuthorController(AuthorService authorService) {
		this.authorService = authorService;
	}

	@PostMapping
	public ResponseEntity<AuthorDetail> create(@RequestBody AuthorCreateRequest request) {
		AuthorDetail author = authorService.create(request.displayName(), request.xUsername());
		return ResponseEntity.status(HttpStatus.CREATED).body(author);
	}

	@GetMapping
	public List<AuthorSummary> search(@RequestParam(required = false) String keyword) {
		return authorService.search(keyword);
	}
}
