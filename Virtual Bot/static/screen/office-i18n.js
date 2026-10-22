import { getLang } from './i18n.js';
export const OFFICE_TEXT = {
  en: {
    title:'Claude’s office', zoomIn:'Zoom in', zoomOut:'Zoom out', overview:'See the whole house', follow:'Follow Claude',
    teleport:'Teleport me', teleportHint:'Tap open floor to move here', teleportKeys:'Arrow keys move the cursor; Enter places you; Escape cancels', teleportCancel:'Cancel teleport', teleportInvalid:'Choose a clear spot', teleportDone:'You are here',
    dozing:'A little more sleep…', calling:'Time to wake up', play:'Playing with a ball', water:'Watering the plant', stretch:'Having a stretch',
    visitor:'Join Claude', visitorOff:'Leave the office', visitorName:'You', chat:'Chat', settings:'Settings', classic:'Classic face',
    corridor:'Hallway', library:'Library', work:'Study', sleep:'Bedroom', kitchen:'Kitchen', lounge:'Lounge',
    idle:'Taking a little break', working:'Working at the computer', read:'Looking through memories', organize:'Sorting memories',
    talking:'Ready to chat', help:'Could use a hand', sleeping:'Sound asleep', waking:'Just a moment…',
    leisure:'Reading for pleasure', eat:'Having a bite', offline:'Waiting for the bot', moving:'On the way to {room}',
  },
  uk: {
    title:'Офіс Клода', zoomIn:'Наблизити', zoomOut:'Віддалити', overview:'Показати весь дім', follow:'Стежити за Клодом',
    teleport:'Телепортувати мене', teleportHint:'Торкнися вільного місця', teleportKeys:'Стрілки рухають курсор; Enter переміщує; Escape скасовує', teleportCancel:'Скасувати телепорт', teleportInvalid:'Обери вільне місце', teleportDone:'Ти тут',
    dozing:'Ще трішки посплю…', calling:'Час прокидатися', play:'Граюся м’ячем', water:'Поливаю рослину', stretch:'Потягуюся',
    visitor:'Приєднатися до Клода', visitorOff:'Вийти з офісу', visitorName:'Ти', chat:'Чат', settings:'Налаштування', classic:'Звичне обличчя',
    corridor:'Коридор', library:'Бібліотека', work:'Кабінет', sleep:'Спальня', kitchen:'Кухня', lounge:'Вітальня',
    idle:'Трохи відпочиваю', working:'Працюю за комп’ютером', read:'Переглядаю спогади', organize:'Упорядковую спогади',
    talking:'Готовий поспілкуватися', help:'Потрібна допомога', sleeping:'Міцно сплю', waking:'Ще хвилиночку…',
    leisure:'Читаю для задоволення', eat:'Перекушую', offline:'Чекаю на бота', moving:'Йду: {room}',
  },
};
export function ot(key, values={}) {
  let text=(OFFICE_TEXT[getLang()]||OFFICE_TEXT.en)[key]||OFFICE_TEXT.en[key]||key;
  for(const [name,value] of Object.entries(values))text=text.replaceAll('{'+name+'}',value);
  return text;
}
