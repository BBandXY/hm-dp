package com.hmdp.controller;

import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.utils.SystemConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("upload")
public class UploadController {

    private static final Set<String> ALLOWED_IMAGE_SUFFIXES = new HashSet<>(
            Arrays.asList("jpg", "jpeg", "png", "gif", "webp")
    );
    private static final Path UPLOAD_ROOT = Paths.get(SystemConstants.IMAGE_UPLOAD_DIR)
            .toAbsolutePath()
            .normalize();

    @PostMapping("blog")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        try {
            if (image == null || image.isEmpty()) {
                return Result.fail("上传文件不能为空");
            }
            // 获取原始文件名称
            String originalFilename = image.getOriginalFilename();
            // 生成新文件名
            String fileName = createNewFileName(originalFilename);
            // 保存文件
            Path target = resolveInsideUploadRoot(fileName);
            Files.createDirectories(target.getParent());
            image.transferTo(target.toFile());
            // 返回结果
            log.debug("文件上传成功，{}", fileName);
            return Result.ok(fileName);
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        } catch (IOException e) {
            throw new RuntimeException("文件上传失败", e);
        }
    }

    @DeleteMapping("/blog")
    public Result deleteBlogImg(@RequestParam("name") String filename) {
        try {
            Path file = resolveInsideUploadRoot(filename);
            if (Files.isDirectory(file)) {
                return Result.fail("错误的文件名称");
            }
            Files.deleteIfExists(file);
            return Result.ok();
        } catch (IOException | IllegalArgumentException e) {
            log.warn("删除图片失败，filename={}", filename, e);
            return Result.fail("错误的文件名称");
        }
    }

    private String createNewFileName(String originalFilename) {
        if (StrUtil.isBlank(originalFilename) || !originalFilename.contains(".")) {
            throw new IllegalArgumentException("文件名或扩展名无效");
        }
        // 获取后缀
        String suffix = StrUtil.subAfter(originalFilename, ".", true).toLowerCase();
        if (!ALLOWED_IMAGE_SUFFIXES.contains(suffix)) {
            throw new IllegalArgumentException("仅允许上传 jpg、jpeg、png、gif 或 webp 图片");
        }
        // 生成目录
        String name = UUID.randomUUID().toString();
        int hash = name.hashCode();
        int d1 = hash & 0xF;
        int d2 = (hash >> 4) & 0xF;
        // 生成文件名
        return StrUtil.format("/blogs/{}/{}/{}.{}", d1, d2, name, suffix);
    }

    private Path resolveInsideUploadRoot(String relativeName) {
        if (StrUtil.isBlank(relativeName)) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String normalizedName = relativeName.replace('\\', '/');
        while (normalizedName.startsWith("/")) {
            normalizedName = normalizedName.substring(1);
        }
        Path target = UPLOAD_ROOT.resolve(normalizedName).normalize();
        if (!target.startsWith(UPLOAD_ROOT)) {
            throw new IllegalArgumentException("非法文件路径");
        }
        return target;
    }
}
