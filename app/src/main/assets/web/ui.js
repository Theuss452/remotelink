'use strict';

(() => {
  const shell = document.querySelector('.app-shell');
  const toggle = document.querySelector('#sidebarToggle');
  const side = document.querySelector('#sideColumn');
  if (!shell || !toggle || !side) return;

  const key = 'remotelink_sidebar_collapsed';
  const apply = collapsed => {
    shell.classList.toggle('sidebar-collapsed', collapsed);
    toggle.setAttribute('aria-expanded', String(!collapsed));
    toggle.title = collapsed ? 'Mostrar painel lateral' : 'Ocultar painel lateral';
    toggle.textContent = collapsed ? '☰' : '⇥';
  };

  apply(localStorage.getItem(key) === '1');
  toggle.addEventListener('click', () => {
    const next = !shell.classList.contains('sidebar-collapsed');
    localStorage.setItem(key, next ? '1' : '0');
    apply(next);
  });
})();
