package com.example.RSW.controller;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import com.example.RSW.service.NotificationService;
import com.example.RSW.service.VetCertificateService;
import com.example.RSW.vo.VetCertificate;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import com.example.RSW.vo.Rq;
import com.example.RSW.vo.Member;
import com.example.RSW.vo.ResultData;
import com.example.RSW.util.Ut;
import com.example.RSW.service.MemberService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Controller
public class UsrMemberController {

    // 카카오 REST API 키 주입
    @Value("${kakao.rest-api-key}")
    private String kakaoRestApiKey;

    // 카카오 리디렉트 URI 주입
    @Value("${kakao.redirect-uri}")
    private String kakaoRedirectUri;

    @Value("${kakao.client-secret}")
    private String kakaoClientSecret;

    @Autowired
    private Rq rq;

    @Autowired
    private MemberService memberService;

    @Autowired
    private VetCertificateService vetCertificateService;

    @Autowired
    private Cloudinary cloudinary;

    @Autowired
    private NotificationService notificationService;


    @RequestMapping("/usr/member/doLogout")
    public String doLogout(HttpServletRequest req) {

        Rq rq = (Rq) req.getAttribute("rq");

        rq.logout();

        return "redirect:/";
    }

    @RequestMapping("/usr/member/logout-complete")
    @ResponseBody
    public String logoutComplete(HttpServletRequest req, HttpServletResponse resp) {
        Rq rq = new Rq(req, resp, memberService);
        rq.logout();
        req.getSession().removeAttribute("kakaoAccessToken");  // 서버 세션, 토큰 삭제

        return """
                    <script>
                        if(window.opener) {
                            window.opener.postMessage("kakaoLogoutComplete", "*");
                            window.close();
                        } else {
                            location.href = "/";
                        }
                    </script>
                """;
    }


    @RequestMapping("/usr/member/service-logout-popup")
    @ResponseBody
    public String serviceLogoutPopup(HttpServletRequest req, HttpServletResponse resp) {
        // 세션 종료 로직
        Rq rq = new Rq(req, resp, memberService);
        rq.logout();

        // 디버깅용 로그 추가
        System.out.println("DEBUG: service-logout-popup 컨트롤러 호출됨");

        return """
                    <script>
                        if(window.opener) {
                            console.log('DEBUG: serviceLogoutComplete 메시지 전송');
                            window.opener.postMessage("serviceLogoutComplete", "*");
                            window.close();
                        } else {
                            location.href = "/";
                        }
                    </script>
                """;
    }


    @RequestMapping("/usr/member/login")
    public String showLogin(HttpServletRequest req, Model model) {

        model.addAttribute("kakaoRestApiKey", kakaoRestApiKey);
        model.addAttribute("kakaoRedirectUri", kakaoRedirectUri);

        return "/usr/member/login";
    }

    @RequestMapping("/usr/member/doLogin")
    @ResponseBody
    public String doLogin(HttpServletRequest req, HttpServletResponse resp, String loginId, String loginPw,
                          @RequestParam(defaultValue = "/") String afterLoginUri) {

        // 세션에서 rq 객체 가져오기
        Rq rq = (Rq) req.getSession().getAttribute("rq");

        // rq 객체가 없다면 새로운 rq 객체를 생성하여 세션에 저장
        if (rq == null) {
            // 새로운 rq 객체 생성, resp 객체도 전달
            rq = new Rq(req, resp, memberService);
            req.getSession().setAttribute("rq", rq);  // 세션에 rq 객체 저장
        }

        // 로그인 필수 값 체크
        if (Ut.isEmptyOrNull(loginId)) {
            return Ut.jsHistoryBack("F-1", "아이디를 입력해");
        }
        if (Ut.isEmptyOrNull(loginPw)) {
            return Ut.jsHistoryBack("F-2", "비밀번호를 입력해");
        }

        // 로그인 시 회원정보를 가져옵니다.
        Member member = memberService.getMemberByLoginId(loginId);

        // 회원이 없으면 에러 반환
        if (member == null) {
            return Ut.jsHistoryBack("F-3", Ut.f("%s는(은) 없는 아이디야", loginId));
        }

        // 비밀번호 해시값 비교
        if (!member.getLoginPw().equals(Ut.sha256(loginPw))) {
            return Ut.jsHistoryBack("F-4", Ut.f("비밀번호가 일치하지 않습니다!!!!!"));
        }

        if (member.isDelStatus()) {
            return Ut.jsHistoryBack("F-5", "탈퇴한 회원입니다.");
        }

        // 로그인 처리 후 rq 객체에 회원 정보를 설정
        rq.login(member);

        // 로그인 후 rq 객체를 세션에 저장하여 이후 요청에서도 사용
        req.getSession().setAttribute("rq", rq);  // 세션에 rq 객체 저장


        return Ut.jsReplace("S-1", Ut.f("%s님 환영합니다", member.getNickname()), afterLoginUri);
    }


    @RequestMapping("/usr/member/join")
    public String showJoin(HttpServletRequest req) {
        return "/usr/member/join";
    }

    @RequestMapping("/usr/member/doJoin")
    @ResponseBody
    public String doJoin(HttpServletRequest req, String loginId, String loginPw, String name, String nickname,
                         String cellphone, String email, String address, String authName) {

        // 필수 입력값 체크
        if (Ut.isEmptyOrNull(loginId)) {
            return Ut.jsHistoryBack("F-1", "아이디를 입력해");
        }
        if (Ut.isEmptyOrNull(loginPw)) {
            return Ut.jsHistoryBack("F-2", "비밀번호를 입력해");
        }
        if (Ut.isEmptyOrNull(name)) {
            return Ut.jsHistoryBack("F-3", "이름을 입력해");
        }
        if (Ut.isEmptyOrNull(nickname)) {
            return Ut.jsHistoryBack("F-4", "닉네임을 입력해");
        }
        if (Ut.isEmptyOrNull(cellphone)) {
            return Ut.jsHistoryBack("F-5", "전화번호를 입력해");
        }
        if (Ut.isEmptyOrNull(email)) {
            return Ut.jsHistoryBack("F-6", "이메일을 입력해");
        }
        if (Ut.isEmptyOrNull(address)) {
            return Ut.jsHistoryBack("F-7", "주소를 입력해");
        }
        if (Ut.isEmptyOrNull(authName)) {
            return Ut.jsHistoryBack("F-8", "인증명을 입력해");
        }

        // 비밀번호 해시화
        String hashedLoginPw = Ut.sha256(loginPw);

        // 무조건 일반회원으로 가입
        int fixedAuthLevel = 1;

        // 회원가입 처리
        ResultData joinRd = memberService.join(loginId, hashedLoginPw, name, nickname, cellphone, email, address, authName, fixedAuthLevel);

        if (joinRd.isFail()) {
            return Ut.jsHistoryBack(joinRd.getResultCode(), joinRd.getMsg());
        }

        // 성공 후 로그인 페이지로 리디렉션
        return Ut.jsReplace(joinRd.getResultCode(), joinRd.getMsg(), "../member/login");
    }


    // 마이페이지
    @RequestMapping({"/usr/member/myPage", "/usr/member/mypage"})
    public String showMyPage(HttpServletRequest req, Model model) {

        Rq rq = (Rq) req.getAttribute("rq");
        Member loginedMember = rq.getLoginedMember();

        VetCertificate cert = vetCertificateService.getCertificateByMemberId(loginedMember.getId());
        model.addAttribute("cert", cert);

        model.addAttribute("member", loginedMember);

        return "usr/member/myPage";
    }

    @RequestMapping("/usr/member/checkPw")
    public String showCheckPw() {
        return "usr/member/checkPw";
    }

    @RequestMapping("/usr/member/doCheckPw")
    public void doCheckPw(HttpServletRequest req, HttpServletResponse resp, String loginPw) throws IOException {
        Rq rq = (Rq) req.getAttribute("rq");

        // 소셜 로그인 회원은 비밀번호 확인 없이 바로 이동
        if (rq.getLoginedMember().isSocialMember()) {
            resp.sendRedirect("modify");
            return;
        }

        // 일반 로그인 회원은 비밀번호 확인
        if (Ut.isEmptyOrNull(loginPw)) {
            rq.printHistoryBack("비밀번호를 입력해 주세요.");
            return;
        }

        if (!rq.getLoginedMember().getLoginPw().equals(Ut.sha256(loginPw))) {
            rq.printHistoryBack("비밀번호가 일치하지 않습니다.");
            return;
        }

        // 성공 시 수정 페이지로 리다이렉트
        resp.sendRedirect("modify");
    }


    @RequestMapping("/usr/member/modify")
    public String showmyModify() {
        return "usr/member/modify";
    }

    @RequestMapping("/usr/member/doModify")
    @ResponseBody
    public String doModify(HttpServletRequest req,
                           @RequestParam(required = false) String loginPw,
                           @RequestParam String name,
                           @RequestParam String nickname,
                           @RequestParam String cellphone,
                           @RequestParam String email,
                           @RequestParam(required = false) MultipartFile photoFile,
                           @RequestParam String address) {

        Rq rq = (Rq) req.getAttribute("rq");

        if (Ut.isEmptyOrNull(name)) return Ut.jsHistoryBack("F-3", "이름을 입력하세요.");
        if (Ut.isEmptyOrNull(nickname)) return Ut.jsHistoryBack("F-4", "닉네임을 입력하세요.");
        if (Ut.isEmptyOrNull(cellphone)) return Ut.jsHistoryBack("F-5", "전화번호를 입력하세요.");
        if (Ut.isEmptyOrNull(email)) return Ut.jsHistoryBack("F-6", "이메일을 입력하세요.");

        String photoUrl = null;

        // 1단계: 업로드 파일 확인
        System.out.println("📸 업로드된 파일: " + (photoFile != null ? photoFile.getOriginalFilename() : "파일 없음"));

        // 2단계: 클라우디너리 업로드
        if (photoFile != null && !photoFile.isEmpty()) {
            try {
                System.out.println("📤 Cloudinary 업로드 시작");
                Map uploadResult = cloudinary.uploader().upload(photoFile.getBytes(), ObjectUtils.emptyMap());
                photoUrl = (String) uploadResult.get("secure_url");
                System.out.println("✅ Cloudinary 업로드 완료: " + photoUrl);
            } catch (IOException e) {
                System.out.println("❌ Cloudinary 업로드 실패: " + e.getMessage());
                return Ut.jsHistoryBack("F-7", "사진 업로드 실패: " + e.getMessage());
            }
        }

        // 3단계: 서비스 호출
        int memberId = rq.getLoginedMemberId();

        System.out.println("📝 전달할 회원정보");
        System.out.println("이름: " + name);
        System.out.println("닉네임: " + nickname);
        System.out.println("전화번호: " + cellphone);
        System.out.println("이메일: " + email);
        System.out.println("비밀번호 있음?: " + (loginPw != null && !loginPw.isBlank()));
        System.out.println("사진 URL: " + photoUrl);

        ResultData modifyRd;
        if (Ut.isEmptyOrNull(loginPw)) {
            modifyRd = memberService.modifyWithoutPw(memberId, name, nickname, cellphone, email, photoUrl, address);
        } else {
            modifyRd = memberService.modify(memberId, loginPw, name, nickname, cellphone, email, photoUrl);
        }

        // 4단계: 세션 최신화
        Member updatedMember = memberService.getMemberById(memberId);
        rq.setLoginedMember(updatedMember);
        System.out.println("🧩 세션 로그인 사용자 갱신 완료");

        return Ut.jsReplace(modifyRd.getResultCode(), modifyRd.getMsg(), "../member/myPage");
    }


    @RequestMapping("/usr/member/getLoginIdDup")
    @ResponseBody
    public ResultData getLoginIdDup(String loginId) {

        if (Ut.isEmpty(loginId)) {
            return ResultData.from("F-1", "아이디를 입력해주세요");
        }

        Member existsMember = memberService.getMemberByLoginId(loginId);

        if (existsMember != null) {
            return ResultData.from("F-2", "해당 아이디는 이미 사용중이야", "loginId", loginId);
        }

        return ResultData.from("S-1", "사용 가능!", "loginId", loginId);
    }

    @RequestMapping("/usr/member/findLoginId")
    public String showFindLoginId() {

        return "usr/member/findLoginId";
    }

    @RequestMapping("/usr/member/doFindLoginId")
    @ResponseBody
    public String doFindLoginId(@RequestParam(defaultValue = "/usr/member/login") String afterFindLoginIdUri,
                                String name, String email) {

        Member member = memberService.getMemberByNameAndEmail(name, email);

        if (member == null) {
            return Ut.jsHistoryBack("F-1", "너는 없는 사람이야");
        }

        return Ut.jsReplace("S-1", Ut.f("너의 아이디는 [ %s ] 야", member.getLoginId()), afterFindLoginIdUri);
    }


    @RequestMapping("/usr/member/findLoginPw")
    public String showFindLoginPw() {

        return "usr/member/findLoginPw";
    }

    @RequestMapping("/usr/member/doFindLoginPw")
    @ResponseBody
    public String doFindLoginPw(@RequestParam(defaultValue = "/") String afterFindLoginPwUri, String loginId,
                                String email) {

        Member member = memberService.getMemberByLoginId(loginId);

        if (member == null) {
            return Ut.jsHistoryBack("F-1", "너는 없는 사람이야");
        }

        if (member.getEmail().equals(email) == false) {
            return Ut.jsHistoryBack("F-2", "일치하는 이메일이 없는데?");
        }

        ResultData notifyTempLoginPwByEmailRd = memberService.notifyTempLoginPwByEmail(member);

        return Ut.jsReplace(notifyTempLoginPwByEmailRd.getResultCode(), notifyTempLoginPwByEmailRd.getMsg(),
                afterFindLoginPwUri);
    }

    @RequestMapping("/usr/member/doWithdraw")
    @ResponseBody
    public String doWithdraw(HttpServletRequest req) {
        Rq rq = (Rq) req.getAttribute("rq");

        if (!rq.isLogined()) {
            return Ut.jsHistoryBack("F-1", "로그인 후 이용해주세요.");
        }

        memberService.withdrawMember(rq.getLoginedMemberId());
        rq.logout(); // 세션 종료

        return Ut.jsReplace("S-1", "회원 탈퇴가 완료되었습니다.", "/");
    }

    @RequestMapping("/usr/member/vetCert")
    public String showVetCertForm(HttpServletRequest req, Model model) {
        Rq rq = (Rq) req.getAttribute("rq");

        // 수의사 신청자인지 확인
        if (!"수의사".equals(rq.getLoginedMember().getAuthName())) {
            model.addAttribute("errorMsg", "수의사만 인증서 제출이 가능합니다.");
            return "common/error";
        }

        return "usr/member/vetCertUpload"; // JSP 경로
    }

    @RequestMapping("/usr/member/doVetCertUpload")
    @ResponseBody
    public String doVetCertUpload(HttpServletRequest req, @RequestParam("file") MultipartFile file) {
        Rq rq = (Rq) req.getAttribute("rq");

        if (file.isEmpty()) {
            return Ut.jsReplace("F-1", "❗ 파일을 선택해주세요.", "/usr/member/myPage");
        }

        try {
            // 기존 인증서 삭제
            VetCertificate existing = vetCertificateService.getCertificateByMemberId(rq.getLoginedMemberId());
            if (existing != null) {
                vetCertificateService.deleteCertificateWithFile(existing);
            }

            String originalFilename = file.getOriginalFilename();
            if (originalFilename == null || originalFilename.trim().isEmpty()) {
                return Ut.jsReplace("F-2", "파일명이 유효하지 않습니다.", "/usr/member/myPage");
            }

            String uuid = UUID.randomUUID().toString();
            String savedFileName = uuid + "_" + originalFilename;
            String uploadDir = "C:/upload/vet_certificates";

            File dir = new File(uploadDir);
            if (!dir.exists()) dir.mkdirs();

            File savedFile = new File(uploadDir + "/" + savedFileName);
            file.transferTo(savedFile);

            VetCertificate cert = new VetCertificate();
            cert.setMemberId(rq.getLoginedMemberId());
            cert.setFileName(originalFilename);
            cert.setFilePath(savedFileName);
            cert.setUploadedAt(LocalDateTime.now());
            cert.setApproved(0);

            System.out.println("📥 저장될 인증서: " + cert.toString());

            vetCertificateService.registerCertificate(cert);
            memberService.updateVetCertInfo(rq.getLoginedMemberId(), savedFileName, 0);

            // 인증서 업로드 성공 후 관리자에게 알림 전송
            notificationService.sendNotificationToAdmins(rq.getLoginedMemberId());


            return """
                    <html>
                    <head>
                      <meta charset="UTF-8">
                      <script>
                        alert('✅ 수의사 인증서가 등록되었습니다. 관리자 승인을 기다려주세요.');
                        location.replace('myCert');
                      </script>
                    </head>
                    <body></body>
                    </html>
                    """;

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("❌ 업로드 예외 발생: " + e.getMessage());

            return """
                    <html>
                    <head>
                      <meta charset="UTF-8">
                      <script>
                        alert('⚠ 업로드 중 오류가 발생했습니다. 다시 시도해주세요.');
                        location.replace('/usr/member/myPage');
                      </script>
                    </head>
                    <body></body>
                    </html>
                    """;
        }
    }


    @RequestMapping("/usr/member/myCert")
    public String showMyCertificate(HttpServletRequest req, Model model) {
        Rq rq = (Rq) req.getAttribute("rq");

        VetCertificate cert = vetCertificateService.getCertificateByMemberId(rq.getLoginedMemberId());

        model.addAttribute("cert", cert);
        return "usr/member/myCert";
    }

    @RequestMapping("/usr/member/deleteVetCert")
    @ResponseBody
    public String deleteVetCert(HttpServletRequest req) {
        Rq rq = (Rq) req.getAttribute("rq");

        VetCertificate cert = vetCertificateService.getCertificateByMemberId(rq.getLoginedMemberId());

        if (cert == null) {
            return Ut.jsHistoryBack("F-1", "삭제할 인증서가 없습니다.");
        }

        vetCertificateService.deleteCertificateWithFile(cert);

        return Ut.jsReplace("S-1", "인증서가 삭제되었습니다.", "/usr/member/myCert");
    }

    // 카카오 로그인
    @RequestMapping("/usr/member/kakao")
    public void kakaoPopupCallback(@RequestParam("code") String code,
                                   HttpServletRequest req, HttpServletResponse resp) throws IOException {

        String tokenUrl = "https://kauth.kakao.com/oauth/token";

        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders tokenHeaders = new HttpHeaders();
        tokenHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> tokenParams = new LinkedMultiValueMap<>();
        tokenParams.add("grant_type", "authorization_code");
        tokenParams.add("client_id", kakaoRestApiKey); // 카카오 REST API 키
        tokenParams.add("redirect_uri", "http://localhost:8080/usr/member/kakao"); // 고정값
        tokenParams.add("client_secret", kakaoClientSecret); // 카카오 클라이언트 시크릿
        tokenParams.add("code", code);

        HttpEntity<MultiValueMap<String, String>> tokenRequest = new HttpEntity<>(tokenParams, tokenHeaders);
        ResponseEntity<Map> tokenResponse = restTemplate.postForEntity(tokenUrl, tokenRequest, Map.class);

        String accessToken = (String) tokenResponse.getBody().get("access_token");

        HttpHeaders profileHeaders = new HttpHeaders();
        profileHeaders.set("Authorization", "Bearer " + accessToken);
        HttpEntity<?> profileRequest = new HttpEntity<>(profileHeaders);

        ResponseEntity<Map> profileResponse = restTemplate.exchange(
                "https://kapi.kakao.com/v2/user/me",
                HttpMethod.GET,
                profileRequest,
                Map.class
        );

        Map properties = (Map) profileResponse.getBody().get("properties");

        String socialId = String.valueOf(profileResponse.getBody().get("id"));
        String name = (String) properties.get("nickname");

        String provider = "kakao";
        String email = ""; // 이메일은 비워둠

        // 기존 사용자 조회 또는 새로 생성
        Member member = memberService.getOrCreateSocialMember(provider, socialId, email, name);

        // 세션 등록
        Rq rq = new Rq(req, resp, memberService);
        rq.login(member);
        req.getSession().setAttribute("rq", rq);
        req.getSession().setAttribute("kakaoAccessToken", accessToken); // 자동 로그인용 저장


        // ✅ 팝업 닫고 부모 창 새로고침
        resp.setContentType("text/html; charset=UTF-8");
        PrintWriter out = resp.getWriter();
        out.println("<script>");
        out.println("localStorage.setItem('kakaoAccessToken', '" + accessToken + "');"); // ✅ 자동 로그인용 토큰 저장
        out.println("window.opener.location.href = '/';");
        out.println("window.close();");
        out.println("</script>");

    }


    // 카카오 팝업 로그인 처리용 REST API 컨트롤러 메서드
    @PostMapping("/usr/member/social-login")
    @ResponseBody
    public ResultData<?> kakaoSocialLogin(@RequestBody Map<String, Object> payload,
                                          HttpServletRequest req, HttpServletResponse resp) {

        String provider = (String) payload.get("provider"); // "kakao"
        String socialId = String.valueOf(payload.get("socialId"));
        String name = (String) payload.get("name");
        String email = (String) payload.get("email");

        Member member = memberService.getOrCreateSocialMember(provider, socialId, email, name);

        Rq rq = new Rq(req, resp, memberService);
        rq.login(member);
        req.getSession().setAttribute("rq", rq);

        return ResultData.from("S-1", "로그인 성공");
    }

    @RequestMapping("/usr/member/kakao-popup-login")
    public void kakaoPopupRedirect(@RequestParam(value = "token", required = false) String accessTokenParam, HttpServletRequest req, HttpServletResponse resp) throws IOException {

        String accessToken = accessTokenParam != null
                ? accessTokenParam
                : (String) req.getSession().getAttribute("kakaoAccessToken");

        if (accessToken != null) {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);
            HttpEntity<?> entity = new HttpEntity<>(headers);

            try {
                ResponseEntity<Map> response = restTemplate.exchange(
                        "https://kapi.kakao.com/v2/user/me",
                        HttpMethod.GET,
                        entity,
                        Map.class
                );

                Map properties = (Map) response.getBody().get("properties");
                String socialId = String.valueOf(response.getBody().get("id"));
                String name = (String) properties.get("nickname");

                Member member = memberService.getOrCreateSocialMember("kakao", socialId, "", name);

                Rq rq = new Rq(req, resp, memberService);
                rq.login(member);
                req.getSession().setAttribute("rq", rq);

                resp.setContentType("text/html; charset=UTF-8");
                PrintWriter out = resp.getWriter();
                out.println("<script>window.opener.location.href = '/'; window.close();</script>");
                return;

            } catch (Exception e) {
                // access_token 만료됐을 때
                req.getSession().removeAttribute("kakaoAccessToken");
            }
        }
        String clientId = "79f2a3a73883a82595a2202187f96cc5";
        String redirectUri = "http://localhost:8080/usr/member/kakao";
        String kakaoAuthUrl = "https://kauth.kakao.com/oauth/authorize" +
                "?client_id=" + clientId +
                "&redirect_uri=" + redirectUri +
                "&response_type=code" +
                "&prompt=login";

        resp.sendRedirect(kakaoAuthUrl);
    }

    @PostMapping("/usr/member/kakao-popup-login")
    @ResponseBody
    public ResponseEntity<?> kakaoPopupLogin(@RequestBody Map<String, String> body,
                                             HttpServletRequest req, HttpServletResponse resp) {
        String token = body.get("token");
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().body("Missing token");
        }

        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + token);
            HttpEntity<?> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    "https://kapi.kakao.com/v2/user/me",
                    HttpMethod.GET,
                    entity,
                    Map.class
            );

            Map properties = (Map) response.getBody().get("properties");
            String socialId = String.valueOf(response.getBody().get("id"));
            String name = (String) properties.get("nickname");

            Member member = memberService.getOrCreateSocialMember("kakao", socialId, "", name);

            Rq rq = new Rq(req, resp, memberService);
            rq.login(member);
            req.getSession().setAttribute("rq", rq);

            return ResponseEntity.ok("자동 로그인 성공");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("로그인 실패");
        }
    }

    @RequestMapping("/usr/member/google")
    public String googleCallback(@RequestParam("code") String code, HttpServletRequest req, HttpServletResponse resp) {

        try {

            RestTemplate restTemplate = new RestTemplate();

            // 1. access token 요청
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("code", code);
            params.add("client_id", "구글 클라이언트 키");
            params.add("client_secret", "구글 클라이언트 시크릿");
            params.add("redirect_uri", "http://localhost:8080/usr/member/google");
            params.add("grant_type", "authorization_code");

            Map<String, Object> tokenResponse = restTemplate.postForObject(
                    "https://oauth2.googleapis.com/token", params, Map.class
            );

            String accessToken = (String) tokenResponse.get("access_token");

            // 2. 사용자 정보 요청
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken);
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<Map> userInfoResponse = restTemplate.exchange(
                    "https://www.googleapis.com/oauth2/v2/userinfo",
                    HttpMethod.GET,
                    entity,
                    Map.class
            );

            Map<String, Object> userInfo = userInfoResponse.getBody();

            String email = (String) userInfo.get("email");
            String name = (String) userInfo.get("name");


            // 3. DB 조회 또는 생성
            Member member = memberService.getOrCreateByEmail(email, name);


            // 4. 세션 저장
            req.getSession().setAttribute("loginedMemberId", member.getId());
            req.getSession().setAttribute("loginedMember", member);

            // ✅ JSP에서도 rq.logined 동작하도록 강제 주입
            req.setAttribute("rq", new Rq(req, resp, memberService));


            return "redirect:/";
        } catch (Exception e) {
            System.out.println("❌ Google 로그인 중 오류 발생:");
            e.printStackTrace();
            return "redirect:/usr/member/login?error=google";
        }
    }

    // 네이버 로그인 콜백 처리
    @RequestMapping("/usr/member/naver")
    public String naverCallback(@RequestParam("code") String code,
                                @RequestParam("state") String state,
                                HttpServletRequest req, HttpServletResponse resp) {

        try {
            RestTemplate restTemplate = new RestTemplate();

            // 🔐 네이버 애플리케이션 등록 정보
            String clientId = "ZdyW5GGtNSgCCaduup7_";          // 네이버 Client ID
            String clientSecret = "pJh4IlGi2_";  // 네이버 Client Secret
            String redirectUri = "http://localhost:8080/usr/member/naver";  // 콜백 URI

            // 1️⃣ access_token 요청 URL 구성
            String tokenUrl = "https://nid.naver.com/oauth2.0/token" +
                    "?grant_type=authorization_code" +
                    "&client_id=" + clientId +
                    "&client_secret=" + clientSecret +
                    "&code=" + code +
                    "&state=" + state;

            // 2️⃣ 토큰 요청 (GET 방식)
            ResponseEntity<Map> tokenResponse = restTemplate.getForEntity(tokenUrl, Map.class);
            String accessToken = (String) tokenResponse.getBody().get("access_token");

            // 3️⃣ 사용자 정보 요청을 위한 헤더 설정
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);
            HttpEntity<?> entity = new HttpEntity<>(headers);

            // 4️⃣ 네이버 사용자 정보 요청
            ResponseEntity<Map> userInfoResponse = restTemplate.exchange(
                    "https://openapi.naver.com/v1/nid/me",
                    HttpMethod.GET,
                    entity,
                    Map.class
            );

            // 5️⃣ 응답 파싱
            Map<String, Object> body = userInfoResponse.getBody();
            Map<String, Object> response = (Map<String, Object>) body.get("response");

            // 6️⃣ 사용자 정보 추출
            String socialId = String.valueOf(response.get("id"));  // 네이버 고유 ID
            String name = (String) response.get("name");           // 이름
            String email = (String) response.get("email");         // 이메일

            // 7️⃣ 회원 DB에 등록 또는 기존 회원 로그인 처리
            Member member = memberService.getOrCreateSocialMember("naver", socialId, email, name);

            // 8️⃣ 세션 등록 (RQ 객체를 이용한 로그인 처리)
            Rq rq = new Rq(req, resp, memberService);
            rq.login(member);
            req.getSession().setAttribute("rq", rq);

            // ✅ 로그인 완료 후 홈으로 리다이렉트
            return "redirect:/";

        } catch (Exception e) {
            // ⚠ 예외 처리 (토큰 요청 실패, 사용자 정보 오류 등)
            e.printStackTrace();
            return "redirect:/usr/member/login?error=naver";
        }
    }

}