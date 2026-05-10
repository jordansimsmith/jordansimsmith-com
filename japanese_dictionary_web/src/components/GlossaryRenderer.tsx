import { createElement, type MouseEvent, type ReactNode } from 'react';
import type { SCElement, SCNode } from '../api/client';

const PASS_THROUGH_TAGS = new Set([
  'div',
  'span',
  'ul',
  'ol',
  'li',
  'details',
  'summary',
  'table',
  'tr',
  'th',
  'td',
  'thead',
  'tbody',
  'tfoot',
  'br',
  'ruby',
  'rt',
  'rp',
]);

interface GlossaryRendererProps {
  node: SCNode;
  onInternalNavigate?: (q: string) => void;
}

export function GlossaryRenderer({
  node,
  onInternalNavigate,
}: GlossaryRendererProps) {
  return <>{renderNode(node, onInternalNavigate, 'root')}</>;
}

function renderNode(
  node: SCNode | undefined,
  onInternalNavigate: ((q: string) => void) | undefined,
  key: string,
): ReactNode {
  if (node === null || node === undefined) {
    return null;
  }
  if (typeof node === 'string') {
    return node;
  }
  if (Array.isArray(node)) {
    return node.map((child, index) =>
      renderNode(child, onInternalNavigate, `${key}.${index}`),
    );
  }
  return renderElement(node, onInternalNavigate, key);
}

function renderElement(
  element: SCElement,
  onInternalNavigate: ((q: string) => void) | undefined,
  key: string,
): ReactNode {
  const { tag } = element;
  if (tag === 'img') {
    return renderImage(element, key);
  }
  if (tag === 'a') {
    return renderAnchor(element, onInternalNavigate, key);
  }
  if (tag && PASS_THROUGH_TAGS.has(tag)) {
    return renderHtml(tag, element, onInternalNavigate, key);
  }
  return renderHtml('div', element, onInternalNavigate, key);
}

function renderImage(element: SCElement, key: string): ReactNode {
  const description = imageDescription(element);
  return (
    <span key={key} className="gloss-image-placeholder" {...dataAttrs(element)}>
      [image: {description}]
    </span>
  );
}

function imageDescription(element: SCElement): string {
  if (typeof element.alt === 'string' && element.alt.length > 0) {
    return element.alt;
  }
  const data = element.data;
  if (
    data &&
    typeof data.description === 'string' &&
    data.description.length > 0
  ) {
    return data.description;
  }
  if (typeof element.src === 'string' && element.src.length > 0) {
    const segments = element.src.split('/');
    return segments[segments.length - 1] ?? element.src;
  }
  return 'image';
}

function renderAnchor(
  element: SCElement,
  onInternalNavigate: ((q: string) => void) | undefined,
  key: string,
): ReactNode {
  const href = typeof element.href === 'string' ? element.href : '';
  const internalQuery = parseInternalLink(href);
  const children = renderNode(element.content, onInternalNavigate, `${key}.c`);

  if (internalQuery !== null) {
    const onClick = (event: MouseEvent<HTMLAnchorElement>) => {
      if (
        event.metaKey ||
        event.ctrlKey ||
        event.shiftKey ||
        event.button !== 0
      ) {
        return;
      }
      event.preventDefault();
      if (onInternalNavigate) {
        onInternalNavigate(internalQuery);
      }
    };
    return (
      <a
        key={key}
        className="gloss-link gloss-link-internal"
        href={`/?q=${encodeURIComponent(internalQuery)}`}
        onClick={onClick}
        {...dataAttrs(element)}
      >
        {children}
      </a>
    );
  }

  return (
    <a
      key={key}
      className="gloss-link gloss-link-external"
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      {...dataAttrs(element)}
    >
      {children}
    </a>
  );
}

function renderHtml(
  tag: string,
  element: SCElement,
  onInternalNavigate: ((q: string) => void) | undefined,
  key: string,
): ReactNode {
  const props: Record<string, unknown> = {
    key,
    ...dataAttrs(element),
  };
  if (typeof element.title === 'string') {
    props.title = element.title;
  }
  if (typeof element.lang === 'string') {
    props.lang = element.lang;
  }
  const children = renderNode(element.content, onInternalNavigate, `${key}.c`);
  return createElement(tag, props, children);
}

function dataAttrs(element: SCElement): Record<string, string> {
  const attrs: Record<string, string> = {};
  const data = element.data;
  if (data) {
    for (const [k, v] of Object.entries(data)) {
      attrs[`data-sc-${k}`] = String(v);
    }
  }
  return attrs;
}

function parseInternalLink(href: string): string | null {
  if (!href.startsWith('?')) {
    return null;
  }
  const params = new URLSearchParams(href.slice(1));
  return params.get('query') ?? params.get('q');
}
