import { createElement, forwardRef } from 'react';
import { createLucideIcon, type LucideProps } from 'lucide-react';
import { SOLAR_ICON_DATA, type SolarIconId } from './data.ts';

/** Reuse the installed SVG renderer; artwork is Solar by 480 Design. */
function solarIcon(id: SolarIconId) {
  const Svg = createLucideIcon(`Solar${id}`, SOLAR_ICON_DATA[id].nodes);
  const Icon = forwardRef<SVGSVGElement, LucideProps>((props, ref) => {
    const svgProps = { strokeWidth: 1.5, ...props, ref, 'data-solar-icon': id };
    return createElement(Svg, svgProps);
  });
  Icon.displayName = `Solar${id}`;
  return Icon;
}

export const SolarSearch = solarIcon('search');
export const SolarImage = solarIcon('image');
export const SolarDocument = solarIcon('document');
export const SolarRead = solarIcon('read');
export const SolarWrite = solarIcon('write');
export const SolarFolder = solarIcon('folder');
export const SolarTasks = solarIcon('tasks');
export const SolarQuestion = solarIcon('question');
export const SolarMusic = solarIcon('music');
export const SolarPlay = solarIcon('play');
export const SolarTerminal = solarIcon('terminal');
export const SolarSettings = solarIcon('settings');
export const SolarCheck = solarIcon('check');
export const SolarAlert = solarIcon('alert');
export const SolarClock = solarIcon('clock');
export const SolarChevron = solarIcon('chevron');
export const SolarWeather = solarIcon('weather');
export const SolarCurrency = solarIcon('currency');
export type SolarIconComponent = typeof SolarSearch;
