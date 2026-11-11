(() => {
  'use strict';

  const bridge = window.AndroidPortalBridge;

  // 빠른 업무 바로가기: 포털 화면에 같은 링크가 있으면 그 주소를 그대로 사용하고,
  // 없을 때만 아래 기본 주소를 사용합니다. (포털 링크가 바뀌어도 앱이 따라감)
  const QUICK_LINKS = [
    { key: 'training', match: 'gwangju-training', title: '법정의무교육', desc: '의무교육 수강하기',
      fallback: 'https://gwangju-training-migration.pages.dev/' },
    { key: 'orders', match: '/orders/request', title: '발주요청서 작성', desc: '물품 및 수업자재 신청',
      fallback: 'https://gwangju-orders-migration.pages.dev/orders/request' },
    { key: 'certificates', match: 'gwangju-student-certificates', title: '수강생 증명서발급', desc: '증명서 발급 시스템',
      fallback: 'https://gwangju-student-certificates.pages.dev/certificates' },
    { key: 'kakao', match: 'pf.kakao.com', title: '카카오톡 1:1 문의', desc: '업무지원포털 상담 채널',
      fallback: 'https://pf.kakao.com/_XExgxeX/chat' },
  ];
  const HR_LINK = { match: 'gwangju-hr', fallback: 'https://gwangju-hr-migration.pages.dev/hr' };

  // 권한 계층: 일반직원(staff) < 일반관리자(admin) < 통합관리자(super_admin)
  const ROLE_LEVEL = { staff: 0, admin: 1, super_admin: 2 };
  const hasPermission = (userRole, requiredRole) =>
    (ROLE_LEVEL[userRole] ?? 0) >= (ROLE_LEVEL[requiredRole] ?? Infinity);

  // 푸시 알림 종류와 볼 수 있는 최소 권한 (네이티브 PortalPush.TYPE_MIN_ROLE 과 같은 값)
  const PUSH_SECTIONS = [
    { key: 'work', title: '업무 알림', items: [
      { key: 'notice', minRole: 'staff', title: '새 공지', desc: '포털에 새 공지가 등록되면 알림' },
      { key: 'order', minRole: 'staff', title: '발주 처리상태', desc: '내 발주의 처리상태가 바뀌면 알림' },
      { key: 'support', minRole: 'staff', title: '업무요청 답변', desc: '내 업무요청에 답변이 등록되면 알림' },
      { key: 'certificate', minRole: 'staff', title: '증명서 수정요청 결과', desc: '내 수정요청이 승인·반려되면 알림' },
    ] },
    { key: 'admin', title: '관리자 알림', items: [
      { key: 'orderAdmin', minRole: 'admin', title: '새 발주 접수', desc: '새 발주요청이 접수되면 알림' },
      { key: 'certificateAdmin', minRole: 'super_admin', title: '새 증명서 수정요청', desc: '증명서 수정요청이 접수되면 알림' },
    ] },
  ];
  const PUSH_ITEMS = PUSH_SECTIONS.flatMap(section => section.items);

  // 권한에 맞는 항목만 남기고, 항목이 하나도 없는 구역은 제외
  const visibleSections = role => PUSH_SECTIONS
    .map(section => ({ ...section, items: section.items.filter(item => hasPermission(role, item.minRole)) }))
    .filter(section => section.items.length > 0);

  let currentRole = 'staff';   // 포털 세션으로 확인되기 전까지는 가장 낮은 권한
  let lastAdminShown;          // 마지막으로 본 포털 관리자 메뉴 노출 여부
  let pushPending = null;      // 저장 결과를 기다리는 스위치 { type, timer }

  function pushState() {
    try {
      return JSON.parse(bridge?.getPushState?.() || '{}');
    } catch {
      return {};
    }
  }

  function appInfo() {
    try {
      return JSON.parse(bridge?.getAppInfo?.() || '{}');
    } catch {
      return {};
    }
  }

  function resolveHref(link, root) {
    const found = Array.from(document.querySelectorAll(`a[href*="${link.match}"]`))
      .find(a => !root.contains(a));
    return found?.href || link.fallback;
  }

  function esc(text) {
    return String(text).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  function tile({ id = '', href = '', title, desc, danger = false, attrs = '' }) {
    const cls = `kp-tile${danger ? ' kp-tile-danger' : ''}`;
    // 포털과 같이 링크는 ↗, 앱 내부 동작은 → 로 표시
    const arrow = href ? '↗' : '→';
    const body = `<span><b>${esc(title)}</b><small>${esc(desc)}</small></span><span class="kp-arrow" aria-hidden="true">${arrow}</span>`;
    return href
      ? `<a class="${cls}" href="${esc(href)}" ${attrs}>${body}</a>`
      : `<button type="button" class="${cls}" ${id ? `id="${id}"` : ''} ${attrs}>${body}</button>`;
  }

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
      pwaOpen.setAttribute('aria-label', '포털 및 앱 설정');
      pwaOpen.dataset.enhanced = '1';
      pwaOpen.addEventListener('click', () => setTimeout(updateNotifUI, 0));
    }

    const adminCtrl = document.getElementById('admin-control');
    if (adminCtrl && adminCtrl.dataset.enhanced !== '1' && adminCtrl.textContent.trim() === '관리자 페이지') {
      adminCtrl.textContent = '관리자';
      adminCtrl.setAttribute('aria-label', '관리자 페이지');
      adminCtrl.dataset.enhanced = '1';
    }
  }

  // 3. 설정 모달에 앱 전용 메뉴 주입
  function buildExtraMenus(dialog) {
    const container = document.createElement('div');
    container.id = 'portal-extra-menus';

    const info = appInfo();
    const version = info.versionName ? `v${esc(info.versionName)}` : '';

    container.innerHTML = `
      <section class="kp-section" aria-labelledby="kp-notif-title">
        <p class="kp-eyebrow">NOTIFICATION</p>
        <div class="kp-row">
          <h3 class="kp-section-title" id="kp-notif-title">기기 알림</h3>
          <span class="kp-badge" id="kp-notif-badge" data-state="off">확인 중</span>
        </div>
        <p class="kp-desc" id="kp-notif-desc"></p>
        <div class="kp-actions">
          <button type="button" class="kp-btn kp-btn-primary" id="kp-notif-allow">알림 허용</button>
          <button type="button" class="kp-btn kp-btn-secondary" id="kp-notif-test">테스트 알림</button>
          <button type="button" class="kp-btn kp-btn-secondary kp-btn-block" id="kp-notif-settings">시스템 알림 설정 열기</button>
        </div>
        <div id="kp-push-area"></div>
      </section>

      <section class="kp-section" aria-labelledby="kp-app-title">
        <p class="kp-eyebrow">APP</p>
        <h3 class="kp-section-title" id="kp-app-title">앱 편의 기능</h3>
        <div class="kp-grid">
          ${tile({ id: 'kp-app-reload', title: '포털 새로고침', desc: '최신 데이터 반영' })}
          ${tile({ id: 'kp-app-clearcache', title: '캐시·세션 정리', desc: '오류 해결 및 재접속' })}
        </div>
      </section>

      <section class="kp-section" aria-labelledby="kp-quick-title">
        <p class="kp-eyebrow">QUICK LINKS</p>
        <h3 class="kp-section-title" id="kp-quick-title">빠른 업무 바로가기</h3>
        <div class="kp-grid">
          ${QUICK_LINKS.map(l => tile({ href: l.fallback, title: l.title, desc: l.desc, attrs: `data-quick="${l.key}"` })).join('')}
        </div>
      </section>

      <section class="kp-section" aria-labelledby="kp-admin-title">
        <p class="kp-eyebrow">ADMIN</p>
        <h3 class="kp-section-title" id="kp-admin-title">관리자 메뉴</h3>
        <div class="kp-grid" id="kp-admin-menu" hidden>
          ${tile({ id: 'kp-menu-edu', title: '의무교육 URL 관리', desc: '수강 링크 설정' })}
          ${tile({ id: 'kp-menu-slack', title: 'Slack 알림 설정', desc: '지원요청 채널 연동' })}
          ${tile({ id: 'kp-menu-push', title: '공지 푸시 알림', desc: '발송 현황 및 재전송' })}
          ${tile({ id: 'kp-menu-trash', title: '게시글 휴지통', desc: '삭제된 게시글 복구' })}
          ${tile({ href: HR_LINK.fallback, title: '인사관리 시스템', desc: 'HR 시스템 바로가기', attrs: 'data-quick="hr"' })}
          ${tile({ id: 'kp-menu-logout', title: '관리자 로그아웃', desc: '현재 세션 종료', danger: true })}
        </div>
        <div class="kp-row" id="kp-admin-login">
          <span class="kp-desc" style="margin:0">관리자 인증 후 포털 관리가 가능합니다.</span>
          <button type="button" class="kp-btn kp-btn-secondary" id="kp-menu-login">로그인</button>
        </div>
      </section>

      ${version ? `<p class="kp-footer">요리광주 업무지원포털 Android 앱 ${version}</p>` : ''}
    `;

    const closeThen = fn => () => { dialog.close(); fn(); };
    const clickById = id => () => document.getElementById(id)?.click();
    const on = (id, handler) => container.querySelector(`#${id}`)?.addEventListener('click', handler);

    on('kp-notif-allow', () => bridge?.requestNotificationPermission?.());
    on('kp-notif-test', () => bridge?.sendTestNotification?.());
    on('kp-notif-settings', () => bridge?.openNotificationSettings?.());
    on('kp-app-reload', closeThen(() => bridge?.reloadPortal?.()));
    on('kp-app-clearcache', () => {
      if (confirm('임시 캐시와 로그인 세션을 정리하고 포털을 다시 시작할까요?')) {
        dialog.close();
        bridge?.clearSessionAndCache?.();
      }
    });
    on('kp-menu-login', closeThen(clickById('admin-control')));
    on('kp-menu-logout', closeThen(clickById('admin-control')));
    on('kp-menu-edu', closeThen(clickById('education-links-manage')));
    on('kp-menu-slack', closeThen(clickById('slack-notifications-manage')));
    on('kp-menu-push', closeThen(clickById('push-notifications-manage')));
    on('kp-menu-trash', closeThen(clickById('board-trash')));

    return container;
  }

  // 알림 종류 스위치: 현재 권한으로 볼 수 있는 항목만 DOM 에 만듭니다.
  function renderPushSwitches() {
    const area = document.getElementById('kp-push-area');
    if (!area) return;
    if (pushPending) { clearTimeout(pushPending.timer); pushPending = null; }

    const push = pushState();
    if (!push.supported) {
      area.innerHTML = '<p class="kp-note">이 앱 버전은 푸시 수신을 지원하지 않습니다. 새 공지는 포털 공지사항에서 확인해 주세요.</p>';
      return;
    }
    const sections = visibleSections(currentRole);
    area.innerHTML = sections.map(section => `
      <div class="kp-switch-group" role="group" aria-labelledby="kp-push-${section.key}">
        <h4 class="kp-switch-group-title" id="kp-push-${section.key}">${esc(section.title)}</h4>
        <div class="kp-switch-list">
          ${section.items.map(item => `
          <button type="button" class="kp-switch" role="switch" data-push-type="${item.key}"
            aria-checked="${push.types?.[item.key] === false ? 'false' : 'true'}">
            <span class="kp-switch-label"><b>${esc(item.title)}</b><small>${esc(item.desc)}</small></span>
            <span class="kp-switch-track" aria-hidden="true"></span>
          </button>`).join('')}
        </div>
      </div>`).join('') + `
      ${push.serverReady === false ? '<p class="kp-note">포털 서버의 알림 발송이 아직 준비되지 않아 알림이 오지 않을 수 있습니다. 관리자에게 문의해 주세요.</p>' : ''}
      <p class="kp-note">발주·업무요청·증명서 알림은 이 앱에서 제출한 요청에 대해서만 전달됩니다. 알림에는 요청 내용이 포함되지 않습니다.${
        sections.some(section => section.key === 'admin') ? ' 관리자 알림은 관리자로 로그인한 기기에만 전달됩니다.' : ''}</p>`;
    area.querySelectorAll('[data-push-type]').forEach(button => {
      button.addEventListener('click', () => togglePushType(button));
    });
  }

  function setPushBusy(busy) {
    const area = document.getElementById('kp-push-area');
    if (!area) return;
    area.toggleAttribute('aria-busy', busy);
    area.querySelectorAll('[data-push-type]').forEach(button => { button.disabled = busy; });
  }

  // 누르면 바로 바뀐 상태를 보여 주고, 저장 결과는 네이티브 이벤트(portal:push-type-result)로 받습니다.
  function togglePushType(button) {
    const type = button.dataset.pushType;
    const item = PUSH_ITEMS.find(i => i.key === type);
    if (pushPending || !item || !hasPermission(currentRole, item.minRole)) return;
    const enabled = button.getAttribute('aria-checked') !== 'true';
    button.setAttribute('aria-checked', String(enabled));
    if (!bridge?.setPushTypeEnabled) return;
    pushPending = { type, timer: setTimeout(() => finishPushToggle({ type, ok: false }), 30000) };
    setPushBusy(true);
    bridge.setPushTypeEnabled(type, enabled);
  }

  function finishPushToggle(detail) {
    if (!pushPending || pushPending.type !== detail.type) return;
    clearTimeout(pushPending.timer);
    pushPending = null;
    setPushBusy(false);
    if (detail.ok) return;
    // 저장 실패: 기기에 실제로 저장되어 있는 값으로 되돌립니다.
    const stored = pushState().types?.[detail.type] !== false;
    document.querySelector(`#kp-push-area [data-push-type="${detail.type}"]`)
      ?.setAttribute('aria-checked', String(stored));
    bridge?.showToast?.('알림 설정을 저장하지 못했습니다. 네트워크 연결을 확인한 뒤 다시 시도해 주세요.');
  }

  window.addEventListener('portal:push-type-result', e => finishPushToggle(e.detail || {}));

  // 포털 관리자 세션으로 권한을 확인합니다. 확인하지 못하면 현재 권한을 그대로 둡니다.
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
    if (role === currentRole) return;
    currentRole = role;
    renderPushSwitches();
  }

  function enhanceSettingsDialog() {
    const dialog = document.getElementById('pwa-dialog');
    if (!dialog) return;

    const title = document.getElementById('pwa-title');
    if (title && title.textContent !== '포털 및 앱 설정') title.textContent = '포털 및 앱 설정';

    let container = dialog.querySelector('#portal-extra-menus');
    if (!container) {
      const content = dialog.querySelector('div[style*="padding"]') || dialog.children[1];
      if (!content) return;
      container = buildExtraMenus(dialog);
      content.appendChild(container);
      renderPushSwitches();
      updateNotifUI();
    }

    // 관리자 로그인 상태는 포털 #admin-menu 노출 여부를 따라감
    const adminMenu = document.getElementById('admin-menu');
    const isAdmin = !!adminMenu && !adminMenu.hidden;
    // 첫 실행과 로그인·로그아웃 때 권한을 다시 확인
    if (isAdmin !== lastAdminShown) {
      lastAdminShown = isAdmin;
      refreshRole();
    }
    const adminGrid = container.querySelector('#kp-admin-menu');
    const adminLogin = container.querySelector('#kp-admin-login');
    if (adminGrid && adminGrid.hidden === isAdmin) adminGrid.hidden = !isAdmin;
    if (adminLogin && adminLogin.hidden !== isAdmin) adminLogin.hidden = isAdmin;

    // 바로가기 주소를 포털 화면의 최신 링크로 동기화
    container.querySelectorAll('[data-quick]').forEach(a => {
      const key = a.dataset.quick;
      const link = key === 'hr' ? HR_LINK : QUICK_LINKS.find(l => l.key === key);
      if (!link) return;
      const href = resolveHref(link, container);
      if (a.href !== href) a.href = href;
    });
  }

  function updateNotifUI(enabledFromEvent) {
    const container = document.getElementById('portal-extra-menus');
    if (!container) return;
    const enabled = typeof enabledFromEvent === 'boolean'
      ? enabledFromEvent
      : !!bridge?.isNotificationEnabled?.();

    const badge = container.querySelector('#kp-notif-badge');
    const desc = container.querySelector('#kp-notif-desc');
    const allow = container.querySelector('#kp-notif-allow');
    if (!badge || !desc || !allow) return;

    badge.dataset.state = enabled ? 'on' : 'off';
    badge.textContent = enabled ? '허용됨' : '꺼짐';
    desc.textContent = enabled
      ? '이 기기에서 업무지원포털 앱의 알림 표시가 허용되어 있습니다.'
      : '기기 알림이 꺼져 있습니다. [알림 허용]을 눌러 권한을 켜 주세요.';
    allow.hidden = enabled;
    allow.style.display = enabled ? 'none' : '';
  }

  // 네이티브에서 권한 변경 결과 / 설정 화면 복귀 시 전달하는 이벤트
  window.addEventListener('portal:notification-state', e => updateNotifUI(!!e.detail?.enabled));

  // 4. 앱 안에서 웹의 "앱 설치 안내" 배너·다이얼로그 노출 방지
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

  // 5. 다이얼로그가 열려 있는 동안 네이티브 당겨서 새로고침 비활성화
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
      enhanceSettingsDialog();
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
