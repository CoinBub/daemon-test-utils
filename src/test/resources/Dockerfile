FROM python:2.7-alpine

RUN pip install python-jsonrpc

COPY .docker/ /

CMD [ "python", "/usr/bin/testrpc.py" ]