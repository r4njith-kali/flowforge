import type { SVGProps } from 'react'

const paths = {
  activity: 'M22 12h-4l-3 9L9 3l-3 9H2',
  arrowDown: 'm7 10 5 5 5-5',
  arrowLeft: 'm15 18-6-6 6-6',
  arrowRight: 'm9 18 6-6-6-6',
  check: 'm5 12 4 4L19 6',
  chevronDown: 'm7 10 5 5 5-5',
  clock: 'M12 8v4l3 2m6-2a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
  close: 'm18 6-12 12M6 6l12 12',
  copy: 'M8 8V4h12v12h-4M4 8h12v12H4z',
  external: 'M15 3h6v6m-11 5L21 3M19 13v7H4V5h7',
  filter: 'M4 5h16M7 12h10m-7 7h4',
  plus: 'M12 5v14m-7-7h14',
  refresh: 'M20 7v5h-5M4 17v-5h5m-4.5-3A8 8 0 0 1 18 6l2 2M4 16l2 2a8 8 0 0 0 13.5-3',
  search: 'm20 20-4.5-4.5M18 10.5a7.5 7.5 0 1 1-15 0 7.5 7.5 0 0 1 15 0Z',
  settings: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8Zm0-6v2m0 16v2m10-10h-2M4 12H2m17.07-7.07-1.41 1.41M6.34 17.66l-1.41 1.41m14.14 0-1.41-1.41M6.34 6.34 4.93 4.93',
  shield: 'M12 22s8-4 8-11V5l-8-3-8 3v6c0 7 8 11 8 11Z',
  sort: 'm8 7 4-4 4 4M12 3v18m4-4-4 4-4-4',
  terminal: 'm4 17 6-6-6-6m8 12h8',
  timer: 'M10 2h4m-2 6v4l3 2m5-2a8 8 0 1 1-16 0 8 8 0 0 1 16 0Z',
  warning: 'M12 9v4m0 4h.01M10.3 3.9 1.8 18.2A2 2 0 0 0 3.5 21h17a2 2 0 0 0 1.7-2.8L13.7 3.9a2 2 0 0 0-3.4 0Z',
  x: 'M18 6 6 18M6 6l12 12',
} as const

export type IconName = keyof typeof paths

export function Icon({
  name,
  ...props
}: SVGProps<SVGSVGElement> & { name: IconName }) {
  return (
    <svg
      aria-hidden="true"
      fill="none"
      stroke="currentColor"
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeWidth="1.7"
      viewBox="0 0 24 24"
      {...props}
    >
      <path d={paths[name]} />
    </svg>
  )
}
