import { createContext } from 'react';

/** A card may submit only to the conversation in which it was rendered. */
export const InteractiveSessionContext = createContext('');

/** Account identity separates cached action state across sign-in changes. */
export const ToolOwnerContext = createContext("");
