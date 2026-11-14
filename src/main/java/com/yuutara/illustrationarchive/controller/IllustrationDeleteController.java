package com.yuutara.illustrationarchive.controller;

import com.yuutara.illustrationarchive.service.IllustrationDeleteService;
import com.yuutara.illustrationarchive.service.IllustrationTrashService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/illustrations")
public class IllustrationDeleteController {

	private final IllustrationDeleteService illustrationDeleteService;
	private final IllustrationTrashService illustrationTrashService;
	private final boolean desktop;

	@Autowired
	public IllustrationDeleteController(
			IllustrationDeleteService illustrationDeleteService,
			IllustrationTrashService illustrationTrashService,
			@Value("${illustration-archive.ui.desktop:false}") boolean desktop
	) {
		this.illustrationDeleteService = illustrationDeleteService;
		this.illustrationTrashService = illustrationTrashService;
		this.desktop = desktop;
	}

	IllustrationDeleteController(IllustrationDeleteService illustrationDeleteService) {
		this(illustrationDeleteService, null, false);
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable long id) {
		if (desktop && illustrationTrashService != null) {
			illustrationTrashService.moveToTrash(id);
		} else {
			illustrationDeleteService.delete(id);
		}
		return ResponseEntity.noContent().build();
	}
}
