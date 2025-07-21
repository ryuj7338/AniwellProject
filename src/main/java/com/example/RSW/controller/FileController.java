package com.example.RSW.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.file.Files;

@Controller
public class FileController {

    // 파일 다운로드 요청 처리
    @GetMapping("/gen/file/download")
    public void downloadFile(@RequestParam("path") String path, HttpServletResponse response) throws IOException {
        // 업로드된 파일의 기본 경로 (로컬 서버 기준)
        // 다른 사용자들도 볼 수 있게 경로 수정하기
        String basePath = "C:/upload/";

        // 요청받은 상대 경로를 OS에 맞게 파일 경로로 변환
        String fullPath = basePath + path.replace("/", File.separator);

        // 디버깅용 로그 출력
        System.out.println("▶ 요청 경로 확인: " + fullPath);

        // 실제 파일 객체 생성
        File file = new File(fullPath);

        // 파일이 존재하지 않으면 404 에러 응답
        if (!file.exists()) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        // MIME 타입 설정 (없을 경우 기본값으로 설정)
        String contentType = Files.probeContentType(file.toPath());
        response.setContentType(contentType != null ? contentType : "application/octet-stream");

        // 파일 이름을 UTF-8로 인코딩하여 다운로드 시 깨지지 않도록 설정
        String encodedName = URLEncoder.encode(file.getName(), "UTF-8").replaceAll("\\+", "%20");

        // 브라우저에서 파일 열기 또는 다운로드 선택 가능하도록 헤더 설정
        response.setHeader("Content-Disposition", "inline; filename=\"" + encodedName + "\"");

        // 응답의 콘텐츠 길이 설정 (바이트 기준)
        response.setContentLengthLong(file.length());

        // 파일을 읽어서 응답 스트림에 복사 (다운로드 처리)
        FileCopyUtils.copy(new FileInputStream(file), response.getOutputStream());
    }
}
