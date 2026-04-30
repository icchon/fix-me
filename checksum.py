import sys

def calculate_checksum(data):
    # Javaの sum += b (byte) と同じ処理
    total_sum = sum(data.encode('ascii'))
    checksum = total_sum % 256
    return f"{checksum:03d}"

def main():
    if len(sys.argv) < 2:
        print("Usage: python gen_fix.py 'SESSION_ID|FIX_BODY_WITHOUT_CHECKSUM'")
        print("Example: python gen_fix.py '000001|8=FIX.4.2|9=65|35=A|49=BROKER_1|56=MARKET_A|'")
        sys.exit(1)

    raw_data = sys.argv[1]

    # チェックサムの計算 (10=XXX| の形にする)
    checksum_val = calculate_checksum(raw_data)
    final_message = f"{raw_data}10={checksum_val}|"

    print(final_message)

if __name__ == "__main__":
    main()
