import './styles.css';
import {headers} from 'next/headers';
export const metadata={title:'LedgerForge',description:'Transactional ledger operations console'};
export default async function Layout({children}:{children:React.ReactNode}) { await headers(); return <html lang="en" suppressHydrationWarning><body>{children}</body></html>; }
