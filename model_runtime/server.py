import argparse
import os

import uvicorn
from dotenv import load_dotenv


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--default-port", type=int, required=True)
    args = parser.parse_args()
    load_dotenv(".env", override=False)
    uvicorn.run("app:app", host="0.0.0.0",
                port=int(os.getenv("SERVER_PORT", str(args.default_port))), workers=1)


if __name__ == "__main__":
    main()
