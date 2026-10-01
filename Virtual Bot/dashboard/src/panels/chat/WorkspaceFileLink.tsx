import { createContext, useContext, type ComponentProps, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { get } from '@/lib/api';
import { workspaceLinkPath, type WorkspaceLocation } from './workspaceLinks';

const Navigation = createContext<{ location: WorkspaceLocation; open: (path: string) => void } | null>(null);

export function WorkspaceLinksProvider({ sessionId, onOpen, children }: {
  sessionId: string; onOpen: (path: string) => void; children: ReactNode;
}) {
  const info = useQuery({
    queryKey: ['workspace-info', sessionId],
    queryFn: () => get<WorkspaceLocation>(`/api/workspace/info?session_id=${encodeURIComponent(sessionId)}`),
    staleTime: Infinity,
  });
  return <Navigation.Provider value={{ location: info.data ?? {}, open: onOpen }}>{children}</Navigation.Provider>;
}

export function WorkspaceLink({ href = '', children, ...props }: ComponentProps<'a'>) {
  const navigation = useContext(Navigation);
  const path = navigation ? workspaceLinkPath(href, navigation.location) : null;
  if (path && navigation) {
    return <a {...props} href={`#/chat?file=${encodeURIComponent(path)}`} onClick={(event) => {
      event.preventDefault();
      navigation.open(path);
    }}>{children}</a>;
  }
  // A disk URL outside this user's workspace cannot be opened by a web page.
  if (/^file:/i.test(href) || href.startsWith('/Users/') || href.startsWith('/home/')) {
    return <span className={props.className}>{children}</span>;
  }
  return <a {...props} href={href} target="_blank" rel="noopener noreferrer">{children}</a>;
}
