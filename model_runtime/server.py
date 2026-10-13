import argparse
import os

import uvicorn
from dotenv import load_dotenv


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--default-port", type=int, required=True)
    args = parser.parse_args()
    load_dotenv(".env", override=False)
    from model_runtime.logs import configure_logging
    port = int(os.getenv("SERVER_PORT", str(args.default_port)))
    configure_logging(f"model-server-{port}")
    uvicorn.run("app:app", host="0.0.0.0", port=port, workers=1)


if __name__ == "__main__":
    main()
