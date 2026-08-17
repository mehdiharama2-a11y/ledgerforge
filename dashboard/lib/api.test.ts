import {describe,expect,it} from 'vitest';
import {formatMoney} from './api';
describe('formatMoney',()=>it('formats exact decimal values',()=>expect(formatMoney('125.50','USD')).toBe('$125.50')));
