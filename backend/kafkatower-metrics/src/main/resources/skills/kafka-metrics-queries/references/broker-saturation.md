# Broker Saturation and Availability

Run when the question is about request handler saturation, broker availability or an overloaded broker.

```promql
avg by (broker) (kafka_server_kafkarequesthandlerpool_requesthandleravgidlepercent{cluster="<name>"})   # <30% saturation, <10% critical
kafka_controller_kafkacontroller_activebrokercount{cluster="<name>"}
```
