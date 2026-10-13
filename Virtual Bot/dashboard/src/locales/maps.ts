const en = {
  label: 'Interactive map', loading: 'Loading map…', failed: 'The map could not be opened.',
  interrupted: 'Map request stopped.', tilesFailed: 'Some map tiles could not load. Check your connection or try again.',
  retry: 'Try again', zoomIn: 'Zoom in', zoomOut: 'Zoom out', reset: 'Recenter',
  open: 'Open in OpenStreetMap', hint: 'Drag to explore. Use the buttons or + / − to zoom.',
  attribution: 'OpenStreetMap contributors',
} as const;
const uk: Record<keyof typeof en, string> = {
  label: 'Інтерактивна карта', loading: 'Завантажую карту…', failed: 'Не вдалося відкрити карту.',
  interrupted: 'Показ карти зупинено.', tilesFailed: 'Частина карти не завантажилася. Перевір підключення або спробуй ще раз.',
  retry: 'Спробувати ще раз', zoomIn: 'Збільшити', zoomOut: 'Зменшити', reset: 'До позначки',
  open: 'Відкрити в OpenStreetMap', hint: 'Перетягуй карту. Змінюй масштаб кнопками або клавішами + / −.',
  attribution: 'Учасники OpenStreetMap',
};
export function t(key: keyof typeof en): string { return (document.documentElement.lang === 'uk' ? uk : en)[key]; }
