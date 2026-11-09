/* Verified public destinations - kept here, not hardcoded per-link, so a link's destination is
 * defined once. Checked against app/src/main/java/com/antolabs/mailmysms/AppInfo.kt. */
const MAILMY_SMS_LINKS = Object.freeze({
  source: 'https://github.com/antojose/mailmysms',
  releases: 'https://github.com/antojose/mailmysms/releases/latest',
  feedback: 'https://github.com/antojose/mailmysms/issues/new',
});

document.querySelectorAll('[data-link]').forEach((el) => {
  const url = MAILMY_SMS_LINKS[el.dataset.link];
  if (url) {
    el.href = url;
    el.rel = 'noopener noreferrer';
    el.target = '_blank';
  } else {
    el.setAttribute('aria-disabled', 'true');
    el.title = 'Link coming soon';
    el.addEventListener('click', (event) => event.preventDefault());
    el.classList.add('unavailable');
  }
});

const toggle = document.querySelector('.menu-toggle');
const menu = document.querySelector('#mobile-menu');
if (toggle && menu) {
  toggle.addEventListener('click', () => {
    const expanded = toggle.getAttribute('aria-expanded') === 'true';
    toggle.setAttribute('aria-expanded', String(!expanded));
    menu.hidden = expanded;
  });
  menu.querySelectorAll('a').forEach((a) => a.addEventListener('click', () => {
    menu.hidden = true;
    toggle.setAttribute('aria-expanded', 'false');
  }));
}

const y = document.getElementById('year');
if (y) y.textContent = String(new Date().getFullYear());
