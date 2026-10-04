import { SOLAR_COMPAT_DATA } from '../solar-icons/compat-data.ts';

/* ReactBits consumes both raw tuples and components. Keep its tuple API while
   replacing artwork with the documented local Solar by 480 Design catalogue. */
function nodes(name) {
  return Object.assign(SOLAR_COMPAT_DATA[name].nodes.map(([tag, attributes]) => [tag, { ...attributes }]), {
    solarName: name.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase()
  });
}

export const Alert02Icon = nodes('TriangleAlert');
export const Archive02Icon = nodes('Archive');
export const ArrowDown01Icon = nodes('ChevronDown');
export const ArrowLeft01Icon = nodes('ChevronLeft');
export const ArrowRight02Icon = nodes('ArrowRight');
export const ArrowUp02Icon = nodes('ArrowUp');
export const Cancel01Icon = nodes('X');
export const CommandLineIcon = nodes('SquareTerminal');
export const CursorPointer01Icon = nodes('MousePointer2');
export const Delete02Icon = nodes('Trash2');
export const Download04Icon = nodes('Download');
export const FavouriteIcon = nodes('Heart');
export const File02Icon = nodes('File');
export const FlashIcon = nodes('Zap');
export const Globe02Icon = nodes('Globe');
export const HelpCircleIcon = nodes('CircleHelp');
export const Layers01Icon = nodes('Layers');
export const Loading03Icon = nodes('LoaderCircle');
export const Mail01Icon = nodes('Mail');
export const Mic01Icon = nodes('Mic');
export const Notification03Icon = nodes('Bell');
export const PaintBoardIcon = nodes('Palette');
export const PencilEdit01Icon = nodes('Pencil');
export const PlusSignIcon = nodes('Plus');
export const RefreshIcon = nodes('RefreshCw');
export const Rocket01Icon = nodes('Rocket');
export const Search01Icon = nodes('Search');
export const Settings02Icon = nodes('Settings');
export const SparklesIcon = nodes('Sparkles');
export const StarIcon = nodes('Star');
export const Attachment01Icon = nodes('Paperclip');
export const Calendar03Icon = nodes('Calendar');
export const ChartLineData01Icon = nodes('ChartLine');
export const CheckIcon = nodes('Check');
export const DockIcon = nodes('PanelBottom');
export const TextFontIcon = nodes('Type');
export const ThumbsUpIcon = nodes('ThumbsUp');
export const Tick02Icon = nodes('Check');
export const Undo02Icon = nodes('Undo2');

/** Preserve inherited color/fill props and the raw geometry consumers. */
export function HugeiconsIcon({ icon, size = 20, strokeWidth = 1.8, color = 'currentColor', fill = 'none', ...rest }) {
  if (!icon) return null;
  if (!Array.isArray(icon)) {
    const Icon = icon;
    return <Icon size={size} strokeWidth={strokeWidth} color={color} {...rest} />;
  }
  return <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width={size} height={size}
    fill={fill} color={color} stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round"
    aria-hidden="true" data-solar-icon={icon.solarName} {...rest}>
    {icon.map(([Tag, { key, ...attributes }], index) => <Tag key={key ?? index} {...attributes} />)}
  </svg>;
}
