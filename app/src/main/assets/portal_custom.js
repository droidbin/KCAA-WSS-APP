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

  let lastAdminShown;   // 마지막으로 본 포털 관리자 메뉴 노출 여부
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
  async function refreshRole() {
    let role;
    try {
      const response = await fetch('/api/admin/session', { cache: 'no-store', credentials: 'same-origin' });
      const session = await response.json();
      role = session.authorized !== true ? 'staff' : session.role === 'unified' ? 'super_admin' : 'admin';
    } catch {
      return;
    }
    bridge?.setPushRole?.(role);
  }

  // 관리자 로그인 상태는 포털 #admin-menu 노출 여부를 따라감. 첫 실행과 로그인·로그아웃 때 네이티브에 알림.
  function syncAdminState() {
    const adminMenu = document.getElementById('admin-menu');
    if (!adminMenu) return;
    const isAdmin = !adminMenu.hidden;
    if (isAdmin === lastAdminShown) return;
    lastAdminShown = isAdmin;
    bridge?.setPortalAdmin?.(isAdmin);
    refreshRole();
  }

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
