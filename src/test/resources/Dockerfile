FROM python:2.7-alpine
EXPOSE 8080

RUN pip install python-jsonrpc

COPY .docker/ /

CMD [ "python", "/usr/bin/testrpc.py" ]