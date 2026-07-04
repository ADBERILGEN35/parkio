import { fireEvent } from '@testing-library/react-native';
import { renderWithProviders } from '@/test/renderWithProviders';
import { TimeField } from '../TimeField';

describe('TimeField', () => {
  it('shows the value split into hour and minute columns', () => {
    const { getByText } = renderWithProviders(
      <TimeField label="Expected return time" value="18:30" onChange={jest.fn()} testIDPrefix="t" />,
    );
    expect(getByText('18')).toBeTruthy();
    expect(getByText('30')).toBeTruthy();
    expect(getByText('Expected return time')).toBeTruthy();
  });

  it('steps hours and minutes through the chevrons', () => {
    const onChange = jest.fn();
    const { getByTestId } = renderWithProviders(
      <TimeField label="Time" value="18:30" onChange={onChange} testIDPrefix="t" />,
    );

    fireEvent.press(getByTestId('t.hour.up'));
    expect(onChange).toHaveBeenLastCalledWith('19:30');

    fireEvent.press(getByTestId('t.hour.down'));
    expect(onChange).toHaveBeenLastCalledWith('17:30');

    fireEvent.press(getByTestId('t.minute.up'));
    expect(onChange).toHaveBeenLastCalledWith('18:35');

    fireEvent.press(getByTestId('t.minute.down'));
    expect(onChange).toHaveBeenLastCalledWith('18:25');
  });

  it('wraps across midnight', () => {
    const onChange = jest.fn();
    const { getByTestId } = renderWithProviders(
      <TimeField label="Time" value="23:55" onChange={onChange} testIDPrefix="t" />,
    );
    fireEvent.press(getByTestId('t.hour.up'));
    expect(onChange).toHaveBeenLastCalledWith('00:55');
    fireEvent.press(getByTestId('t.minute.up'));
    expect(onChange).toHaveBeenLastCalledWith('23:00');
  });

  it('disables the steppers when disabled', () => {
    const onChange = jest.fn();
    const { getByTestId } = renderWithProviders(
      <TimeField label="Time" value="18:30" onChange={onChange} disabled testIDPrefix="t" />,
    );
    fireEvent.press(getByTestId('t.hour.up'));
    expect(onChange).not.toHaveBeenCalled();
  });
});
