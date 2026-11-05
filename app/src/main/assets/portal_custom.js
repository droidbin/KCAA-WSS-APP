(() => {
  'use strict';

  // 1. 모든 페이지에서 브랜드 로고 적용 (법정의무교육, 포털 홈, 수강생 증명서 시스템 등)
  function applyLogo() {
    try {
      // CSP(img-src 'self')를 엄격히 적용하는 서브 시스템(수강생 증명서발급 등)에서도
      // 깨진 이미지 없이 로고가 100% 렌더링되도록 /brand/kcaa-symbol.png 경로를 사용합니다.
      // (안드로이드 WebView의 shouldInterceptRequest가 assets의 신규 투명 로고로 직접 서빙)
      document.querySelectorAll('.brand, a.brand, header .brand').forEach(brand => {
        let img = brand.querySelector('img.brand-logo') || brand.querySelector('img');
        if (!img) {
          // 법정의무교육 페이지처럼 img 태그가 없는 경우 새로 생성하여 제목 앞에 삽입
          img = document.createElement('img');
          img.className = 'brand-logo';
          img.alt = '로고';
          img.src = '/brand/kcaa-symbol.png';
          brand.insertBefore(img, brand.firstChild);
        } else {
          img.classList.add('brand-logo');
          if (!img.src.includes('kcaa-symbol.png')) {
            img.src = '/brand/kcaa-symbol.png';
          }
        }
      });
    } catch (e) {
      console.warn('Logo update error:', e);
    }
  }

  // 2. 헤더 버튼 레이블 및 레이아웃 정리
  function applyHeaderEnhancements() {
    const pwaOpen = document.getElementById('pwa-open');
    if (pwaOpen && !pwaOpen.dataset.enhanced) {
      pwaOpen.innerHTML = '<span style="font-size:13px">⚙️</span> 설정';
      pwaOpen.setAttribute('title', '포털 및 앱 설정');
      pwaOpen.dataset.enhanced = 'true';
    }

    const adminCtrl = document.getElementById('admin-control');
    if (adminCtrl && !adminCtrl.dataset.enhanced) {
      if (adminCtrl.textContent.trim() === '관리자 페이지') {
        adminCtrl.innerHTML = '<span style="font-size:12px">🛡️</span> 관리자';
      }
      adminCtrl.dataset.enhanced = 'true';
    }
  }

  // 3. 설정 다이얼로그(#pwa-dialog) 내용 최적화 및 추가 편의 메뉴 주입
  function enhanceSettingsDialog() {
    const dialog = document.getElementById('pwa-dialog');
    if (!dialog) return;

    // 제목 변경
    const title = document.getElementById('pwa-title');
    if (title && title.textContent !== '⚙️ 포털 및 앱 설정') {
      title.textContent = '⚙️ 포털 및 앱 설정';
    }

    // 앱 설치 버튼 및 안내 문구 숨김
    const installBtn = document.getElementById('pwa-install');
    if (installBtn) installBtn.style.setProperty('display', 'none', 'important');

    const installNote = document.getElementById('pwa-install-note');
    if (installNote) installNote.style.setProperty('display', 'none', 'important');

    const dialogContent = dialog.querySelector('div[style*="padding"]') || dialog.children[1];
    if (!dialogContent) return;

    // 푸시 알림 버튼 클릭 시 Android 13+ 권한 동시 요청
    const enableBtn = document.getElementById('pwa-enable');
    if (enableBtn && !enableBtn.dataset.permissionHooked) {
      enableBtn.addEventListener('click', () => {
        window.AndroidPortalBridge?.requestNotificationPermission?.();
      });
      enableBtn.dataset.permissionHooked = 'true';
    }

    // 커스텀 편의 메뉴가 아직 주입되지 않았다면 주입
    if (!dialog.querySelector('#portal-extra-menus')) {
      const extraContainer = document.createElement('div');
      extraContainer.id = 'portal-extra-menus';
      extraContainer.style.display = 'flex';
      extraContainer.style.flexDirection = 'column';
      extraContainer.style.gap = '12px';
      extraContainer.style.marginTop = '4px';

      // 네이티브 브릿지에서 현재 앱 버전명을 동적으로 조회 (기본값 1.1)
      let currentAppVersion = '1.1.1';
      try {
        const infoJson = window.AndroidPortalBridge?.getAppInfo?.();
        if (infoJson) {
          const parsed = JSON.parse(infoJson);
          if (parsed.versionName) currentAppVersion = parsed.versionName;
        }
      } catch (e) {
        // ignore parse error
      }
      const adminMenu = document.getElementById('admin-menu');
      const isAdminLogged = adminMenu && !adminMenu.hidden;

      extraContainer.innerHTML = `
        <!-- 1. 새 공지사항 푸시 알림 설정 카드 -->
        <div class="modal-card-section" id="modal-notif-section">
          <div class="modal-card-title">🔔 공지사항 푸시 알림</div>
          <div style="display:flex;align-items:center;justify-content:space-between;margin-bottom:8px">
            <span style="font-size:13px;font-weight:700;color:#1e293b">기기 알림 상태</span>
            <span id="notif-status-badge" style="padding:4px 8px;border-radius:6px;font-size:11px;font-weight:700;background:#dcfce7;color:#15803d">● 수신 활성화</span>
          </div>
          <p id="notif-desc-text" style="font-size:12px;color:#64748b;margin:0 0 10px;line-height:1.4">
            새로운 공지사항이 등록되면 기기 알림으로 알려드립니다.
          </p>
          <div style="display:flex;gap:6px">
            <button type="button" class="modal-grid-btn" id="btn-toggle-notif" style="flex:1;text-align:center;align-items:center;background:#2563eb;color:#ffffff;border:none">
              <b style="color:#ffffff" id="btn-toggle-notif-text">🔔 알림 켜기</b>
            </button>
            <button type="button" class="modal-grid-btn" id="btn-test-notif" style="flex:1;text-align:center;align-items:center">
              <b>📱 테스트 알림</b>
            </button>
          </div>
          <button type="button" class="btn-system-settings" id="btn-open-system-notif" style="margin-top:8px">
            ⚙️ 기기 시스템 알림 설정 열기
          </button>
        </div>

        <!-- 2. 앱 환경 및 편의 기능 -->
        <div class="modal-card-section">
          <div class="modal-card-title">📱 앱 편의 기능</div>
          <div class="modal-grid">
            <button type="button" class="modal-grid-btn" id="btn-app-reload">
              <b>🔄 포털 새로고침</b>
              <small>최신 데이터 즉시 반영</small>
            </button>
            <button type="button" class="modal-grid-btn" id="btn-app-clearcache">
              <b>🧹 캐시 및 세션 정리</b>
              <small>오류 해결 및 재접속</small>
            </button>
          </div>
        </div>

        <!-- 3. 주요 업무 바로가기 -->
        <div class="modal-card-section">
          <div class="modal-card-title">⚡ 빠른 업무 바로가기</div>
          <div class="modal-grid">
            <a class="modal-grid-btn" href="https://gwangju-disaster-training.gardengleam1.chatgpt.site" target="_blank" rel="noopener noreferrer">
              <b>🎓 의무교육 수강</b>
              <small>법정의무교육 바로가기</small>
            </a>
            <a class="modal-grid-btn" href="https://airtable.com/app0isYdrgvvQE0vT/pagAzq8GTVCdcdc4d/form" target="_blank" rel="noopener noreferrer">
              <b>📋 발주요청서 작성</b>
              <small>물품 및 수업자재 신청</small>
            </a>
            <a class="modal-grid-btn" href="http://qr.kakao.com/talk/f_.aWEGbE0pMpGOMMtkn9NvmEug-" target="_blank" rel="noopener noreferrer">
              <b>💬 카카오톡 1:1 문의</b>
              <small>업무지원포털 상담 채널</small>
            </a>
            <a class="modal-grid-btn" href="https://gwangju-student-certificates.droidbin.chatgpt.site/certificates" target="_blank" rel="noopener noreferrer">
              <b>📄 수강생 증명서발급</b>
              <small>증명서 발급 시스템</small>
            </a>
          </div>
        </div>

        <!-- 4. 관리자 메뉴 -->
        <div class="modal-card-section">
          <div class="modal-card-title">🛡️ 관리자 메뉴</div>
          ${isAdminLogged ? `
            <div class="modal-grid">
              <button type="button" class="modal-grid-btn" id="btn-menu-edu">
                <b>📝 의무교육 URL 관리</b>
                <small>수강 링크 설정</small>
              </button>
              <button type="button" class="modal-grid-btn" id="btn-menu-slack">
                <b>💬 Slack 알림 설정</b>
                <small>지원요청 채널 연동</small>
              </button>
              <button type="button" class="modal-grid-btn" id="btn-menu-push">
                <b>📢 공지 푸시 알림</b>
                <small>발송 현황 및 재전송</small>
              </button>
              <button type="button" class="modal-grid-btn" id="btn-menu-trash">
                <b>🗑️ 게시글 휴지통</b>
                <small>삭제된 게시글 복구</small>
              </button>
              <a class="modal-grid-btn" href="https://gwangju-hr.droidbin.chatgpt.site/hr" target="_blank" rel="noopener noreferrer">
                <b>👥 인사관리 시스템</b>
                <small>HR 시스템 바로가기</small>
              </a>
              <button type="button" class="modal-grid-btn" id="btn-menu-logout" style="border-color:#fca5a5;background:#fff1f2">
                <b style="color:#b91c1c">🚪 관리자 로그아웃</b>
                <small>현재 세션 종료</small>
              </button>
            </div>
          ` : `
            <div style="display:flex;align-items:center;justify-content:space-between;padding:4px 0">
              <span style="font-size:12px;color:#64748b">관리자 인증 후 포털 관리가 가능합니다.</span>
              <button type="button" class="header-admin" id="btn-menu-login" style="padding:6px 12px;font-size:12px;border-radius:6px;cursor:pointer">로그인</button>
            </div>
          `}
        </div>

        <div style="text-align:center;font-size:11px;color:#94a3b8;margin-top:6px">
          요리광주 업무지원포털 Android 전용 앱 v${currentAppVersion}
        </div>
      `;

      dialogContent.appendChild(extraContainer);

      // 알림 상태 UI 동적 갱신
      function updateNotifUI() {
        const isEnabled = window.AndroidPortalBridge?.isNotificationEnabled?.() ?? true;
        const badge = extraContainer.querySelector('#notif-status-badge');
        const desc = extraContainer.querySelector('#notif-desc-text');
        const btnToggle = extraContainer.querySelector('#btn-toggle-notif');
        const btnToggleText = extraContainer.querySelector('#btn-toggle-notif-text');

        if (badge && desc && btnToggle && btnToggleText) {
          if (isEnabled) {
            badge.textContent = '● 알림 켜짐 (수신 중)';
            badge.style.background = '#dcfce7';
            badge.style.color = '#15803d';
            desc.textContent = '현재 요리광주 업무지원포털의 새로운 공지 알림을 정상 수신하고 있습니다.';
            btnToggle.style.background = '#f1f5f9';
            btnToggle.style.border = '1px solid #cbd5e1';
            btnToggleText.style.color = '#475569';
            btnToggleText.textContent = '알림 권한 허용됨';
          } else {
            badge.textContent = '○ 알림 꺼짐 (차단됨)';
            badge.style.background = '#fee2e2';
            badge.style.color = '#b91c1c';
            desc.textContent = '기기 알림이 꺼져 있습니다. [알림 켜기]를 눌러 공지 알림을 활성화하세요.';
            btnToggle.style.background = '#2563eb';
            btnToggle.style.border = 'none';
            btnToggleText.style.color = '#ffffff';
            btnToggleText.textContent = '🔔 알림 켜기';
          }
        }
      }
      updateNotifUI();

      // 이벤트 바인딩
      extraContainer.querySelector('#btn-toggle-notif')?.addEventListener('click', () => {
        window.AndroidPortalBridge?.requestNotificationPermission?.();
        setTimeout(updateNotifUI, 1200);
      });

      extraContainer.querySelector('#btn-test-notif')?.addEventListener('click', () => {
        window.AndroidPortalBridge?.sendTestNotification?.();
      });

      extraContainer.querySelector('#btn-open-system-notif')?.addEventListener('click', () => {
        window.AndroidPortalBridge?.openNotificationSettings?.();
      });

      extraContainer.querySelector('#btn-app-reload')?.addEventListener('click', () => {
        dialog.close();
        window.AndroidPortalBridge?.reloadPortal?.();
      });

      extraContainer.querySelector('#btn-app-clearcache')?.addEventListener('click', () => {
        if (confirm('임시 캐시 및 로그인 세션을 정리하고 포털을 다시 시작하시겠습니까?')) {
          dialog.close();
          window.AndroidPortalBridge?.clearSessionAndCache?.();
        }
      });

      extraContainer.querySelector('#btn-menu-login')?.addEventListener('click', () => {
        dialog.close();
        document.getElementById('admin-control')?.click();
      });

      extraContainer.querySelector('#btn-menu-logout')?.addEventListener('click', () => {
        dialog.close();
        document.getElementById('admin-control')?.click();
      });

      extraContainer.querySelector('#btn-menu-edu')?.addEventListener('click', () => {
        dialog.close();
        document.getElementById('education-links-manage')?.click();
      });

      extraContainer.querySelector('#btn-menu-slack')?.addEventListener('click', () => {
        dialog.close();
        document.getElementById('slack-notifications-manage')?.click();
      });

      extraContainer.querySelector('#btn-menu-push')?.addEventListener('click', () => {
        dialog.close();
        document.getElementById('push-notifications-manage')?.click();
      });

      extraContainer.querySelector('#btn-menu-trash')?.addEventListener('click', () => {
        dialog.close();
        document.getElementById('board-trash')?.click();
      });
    }
  }

  // 4. 안드로이드 전용 앱 내부에서 웹의 "앱 설치 안내" 배너 및 다이얼로그 노출 방지
  function hideAppInstallBanners() {
    try {
      localStorage.setItem('portal:app-installed', '1');
    } catch {}

    const banner = document.getElementById('android-banner');
    if (banner) {
      banner.hidden = true;
      banner.style.setProperty('display', 'none', 'important');
    }

    const dialogTitle = document.getElementById('dialog-title');
    if (dialogTitle && (dialogTitle.textContent.includes('앱 설치') || dialogTitle.textContent.includes('안드로이드 앱'))) {
      const dialog = document.getElementById('notice-dialog') || document.querySelector('dialog[open]');
      if (dialog && typeof dialog.close === 'function') {
        dialog.close();
      }
    }
  }

  // 전체 실행 루틴
  function runAll() {
    hideAppInstallBanners();
    applyLogo();
    applyHeaderEnhancements();
    enhanceSettingsDialog();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', runAll);
  } else {
    runAll();
  }

  // DOM 동적 변화 관찰
  const observer = new MutationObserver(() => {
    runAll();
  });
  observer.observe(document.documentElement, { childList: true, subtree: true });
})();
