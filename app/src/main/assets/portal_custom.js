(() => {
  'use strict';

  const bridge = window.AndroidPortalBridge;

  // 앱 설정은 네이티브 화면(SettingsActivity)으로 분리되었습니다.
  // 이 스크립트는 포털 화면 쪽 일만 합니다: 로고·헤더 정리, [설정] 버튼 연결,
  // 관리자 로그인 상태와 바로가기 주소를 네이티브에 알려 주기, 앱에서 필요 없는 웹 설치 안내 숨기기.

  // 설정 화면의 바로가기 주소: 포털 화면에 같은 링크가 있으면 그 주소를 네이티브에 알려 줍니다.
  // (없는 키는 네이티브가 기본 주소를 사용 — 포털 링크가 바뀌어도 앱이 따라감)
  const LINK_MATCHES = {
    training: 'gwangju-training',
    orders: '/orders/request',
    certificates: 'gwangju-student-certificates',
    kakao: 'pf.kakao.com',
    hr: 'gwangju-hr',
    inventory: 'gwangju-inventory',
  };

  let lastAdmin;        // 마지막으로 확인한 관리자 로그인 여부 (세션 API 기준)
  let lastRole;         // 마지막으로 네이티브에 알린 푸시 역할
  let lastLinksJson = '';

  // 1. 브랜드 로고 적용 (포털 홈, 법정의무교육, 수강생 증명서 등)
  //    /brand/kcaa-symbol.png 요청은 앱이 내장 투명 로고로 응답합니다 (CSP img-src 'self' 충족).
  function applyLogo() {
    document.querySelectorAll('.brand, a.brand, header .brand').forEach(brand => {
      let img = brand.querySelector('img.brand-logo') || brand.querySelector('img');
      if (!img) {
        img = document.createElement('img');
        img.className = 'brand-logo';
        img.alt = '';
        img.src = '/brand/kcaa-symbol.png';
        brand.insertBefore(img, brand.firstChild);
        return;
      }
      if (!img.classList.contains('brand-logo')) img.classList.add('brand-logo');
      if (!img.src.includes('kcaa-symbol.png')) img.src = '/brand/kcaa-symbol.png';
    });
  }

  // 2. 헤더 버튼 문구를 모바일 폭에 맞게 정리 (포털과 같이 텍스트만 사용)
  function applyHeaderEnhancements() {
    const pwaOpen = document.getElementById('pwa-open');
    if (pwaOpen && pwaOpen.dataset.enhanced !== '1') {
      pwaOpen.textContent = '설정';
      pwaOpen.setAttribute('aria-label', '앱 설정');
      pwaOpen.dataset.enhanced = '1';
    }

    const adminCtrl = document.getElementById('admin-control');
    if (adminCtrl && adminCtrl.dataset.enhanced !== '1' && adminCtrl.textContent.trim() === '관리자 페이지') {
      adminCtrl.textContent = '관리자';
      adminCtrl.setAttribute('aria-label', '관리자 페이지');
      adminCtrl.dataset.enhanced = '1';
    }
  }

  // 3. 포털 헤더의 [설정] 버튼은 포털 팝업 대신 앱 설정 화면을 엽니다.
  //    캡처 단계에서 가로채 포털의 기존 동작(팝업 열기)이 실행되지 않게 합니다.
  document.addEventListener('click', event => {
    const target = event.target;
    if (!bridge?.openAppSettings || !(target instanceof Element) || !target.closest('#pwa-open')) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    bridge.openAppSettings();
  }, true);

  // 4. 포털 관리자 세션으로 권한을 확인해 네이티브에 알립니다. 확인하지 못하면 아무것도 바꾸지 않습니다.
  //    (세션이 끝나도 서버가 기기의 관리자 역할을 유지하는 동안의 권한 처리는 네이티브가 합니다.)
  //    관리자 로그인 상태도 이 응답(authorized)이 기준입니다. 화면 요소(#admin-menu)나 페이지 종류에 의존하지 않아
  //    /admin 같은 관리자 화면에서 로그인해도 반영됩니다. 응답이 포털 세션 형식이 아니면(다른 사이트 등) 아무것도 바꾸지 않습니다.
  let sessionChecking = false;
  async function refreshAdminSession() {
    if (sessionChecking) return;
    sessionChecking = true;
    try {
      const response = await fetch('/api/admin/session', { cache: 'no-store', credentials: 'same-origin' });
      if (!response.ok) return;
      const session = await response.json();
      if (typeof session?.authorized !== 'boolean') return;
      const isAdmin = session.authorized === true;
      const role = !isAdmin ? 'staff' : session.role === 'unified' ? 'super_admin' : 'admin';
      if (isAdmin !== lastAdmin) {
        lastAdmin = isAdmin;
        bridge?.setPortalAdmin?.(isAdmin);
      }
      if (role !== lastRole) {
        lastRole = role;
        bridge?.setPushRole?.(role);
      }
    } catch {
      // 네트워크 오류 등: 이전 상태 유지
    } finally {
      sessionChecking = false;
    }
  }

  // 첫 실행, 포털 관리자 메뉴 노출이 바뀔 때(로그인·로그아웃), 화면이 다시 보일 때, 주기적으로 세션을 다시 확인합니다.
  let lastMenuHidden;
  function syncAdminState() {
    const adminMenu = document.getElementById('admin-menu');
    const menuHidden = adminMenu ? adminMenu.hidden : undefined;
    if (lastAdmin === undefined || menuHidden !== lastMenuHidden) {
      lastMenuHidden = menuHidden;
      refreshAdminSession();
    }
  }
  document.addEventListener('visibilitychange', () => { if (!document.hidden) refreshAdminSession(); });
  window.addEventListener('focus', refreshAdminSession);
  setInterval(() => { if (!document.hidden) refreshAdminSession(); }, 20000);

  // 5. 바로가기 주소를 네이티브에 알림 (바뀐 경우에만)
  function syncLinks() {
    const found = {};
    for (const [key, match] of Object.entries(LINK_MATCHES)) {
      const anchor = document.querySelector(`a[href*="${match}"]`);
      if (anchor?.href) found[key] = anchor.href;
    }
    const json = JSON.stringify(found);
    if (json === lastLinksJson || json === '{}') return;
    lastLinksJson = json;
    bridge?.setPortalLinks?.(json);
  }

  // 6. 앱 안에서 웹의 "앱 설치 안내" 배너·다이얼로그 노출 방지
  function hideAppInstallBanners() {
    try {
      if (localStorage.getItem('portal:app-installed-v2') !== '1') {
        localStorage.setItem('portal:app-installed', '1');
        localStorage.setItem('portal:app-installed-v2', '1');
      }
    } catch {}

    const banner = document.getElementById('android-banner');
    if (banner && !banner.hidden) banner.hidden = true;

    const dialogTitle = document.getElementById('dialog-title');
    if (dialogTitle && /앱 설치|안드로이드 앱/.test(dialogTitle.textContent)) {
      const dialog = document.getElementById('notice-dialog') || document.querySelector('dialog[open]');
      if (dialog?.open && typeof dialog.close === 'function') dialog.close();
    }
  }

  // 7. 다이얼로그(게시글 등)가 열려 있는 동안 네이티브 당겨서 새로고침 비활성화
  //    (다이얼로그 안에서 아래로 끌 때 포털이 새로고침되는 현상 방지)
  let modalOpen = false;
  function syncModalState() {
    const open = !!document.querySelector('dialog[open]');
    if (open === modalOpen) return;
    modalOpen = open;
    bridge?.setModalOpen?.(open);
  }

  function runAll() {
    try {
      syncModalState();
      hideAppInstallBanners();
      applyLogo();
      applyHeaderEnhancements();
      syncAdminState();
      syncLinks();
    } catch (e) {
      console.warn('[portal-app] enhance error:', e);
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', runAll);
  } else {
    runAll();
  }

  // DOM 변경을 한 프레임에 한 번만 처리
  let scheduled = false;
  new MutationObserver(() => {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(() => {
      scheduled = false;
      runAll();
    });
  // hidden 속성 변화(관리자 로그인 시 #admin-menu 노출 등)와 dialog 열림/닫힘(open)도 감지
  }).observe(document.documentElement, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['hidden', 'open'],
  });
})();
