import { SOLAR_COMPAT_DATA } from '../solar-icons/compat-data.ts';

// Original Solar nodes, not installed Lucide artwork. Keep this alias map in
// sync with _icons.jsx when importing another ReactBits component.
const MAP = {
  Alert02Icon: 'TriangleAlert', Archive02Icon: 'Archive', ArrowDown01Icon: 'ChevronDown',
  ArrowLeft01Icon: 'ChevronLeft', ArrowRight02Icon: 'ArrowRight', ArrowUp02Icon: 'ArrowUp',
  Cancel01Icon: 'X', CommandLineIcon: 'SquareTerminal', CursorPointer01Icon: 'MousePointer2',
  Delete02Icon: 'Trash2', Download04Icon: 'Download', FavouriteIcon: 'Heart', File02Icon: 'File',
  FlashIcon: 'Zap', Globe02Icon: 'Globe', HelpCircleIcon: 'CircleHelp', Layers01Icon: 'Layers',
  Loading03Icon: 'LoaderCircle', Mail01Icon: 'Mail', Mic01Icon: 'Mic', Notification03Icon: 'Bell',
  PaintBoardIcon: 'Palette', PencilEdit01Icon: 'Pencil', PlusSignIcon: 'Plus', RefreshIcon: 'RefreshCw',
  Rocket01Icon: 'Rocket', Search01Icon: 'Search', Settings02Icon: 'Settings', SparklesIcon: 'Sparkles',
  StarIcon: 'Star', Attachment01Icon: 'Paperclip', Calendar03Icon: 'Calendar',
  ChartLineData01Icon: 'ChartLine', CheckIcon: 'Check', DockIcon: 'PanelBottom', TextFontIcon: 'Type', ThumbsUpIcon: 'ThumbsUp', Tick02Icon: 'Check', Undo02Icon: 'Undo2',
};
console.log(JSON.stringify(Object.fromEntries(Object.entries(MAP).map(([alias, name]) => [alias, SOLAR_COMPAT_DATA[name].nodes]))));
