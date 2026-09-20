package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.service.IllustrationDeleteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/illustrations")
public class IllustrationDeleteController {

	private final IllustrationDeleteService illustrationDeleteService;

	public IllustrationDeleteController(IllustrationDeleteService illustrationDeleteService) {
		this.illustrationDeleteService = illustrationDeleteService;
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable long id) {
		illustrationDeleteService.delete(id);
		return ResponseEntity.noContent().build();
	}
}
