import { deleteChartUsingPOST, listMyChartByPageUsingPOST } from '@/services/yubi/chartController';
import {
  BarChartOutlined,
  CheckCircleFilled,
  ClockCircleFilled,
  DeleteOutlined,
  ExclamationCircleFilled,
  PlusOutlined,
  ReloadOutlined,
  SearchOutlined,
  SyncOutlined,
} from '@ant-design/icons';
import { history } from '@umijs/max';
import {
  Button,
  Card,
  Col,
  Empty,
  Input,
  message,
  Modal,
  Pagination,
  Popconfirm,
  Row,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import ReactECharts from 'echarts-for-react';
import './index.less';

const { Paragraph, Text, Title } = Typography;
const PAGE_SIZE = 6;

type ChartStatus = 'succeeded' | 'running' | 'waiting' | 'failed' | 'unknown';

/** 后端持久化状态统一为大写；兼容旧记录中的小写状态值。 */
const normalizeStatus = (status?: string): ChartStatus => {
  switch ((status || '').toUpperCase()) {
    case 'SUCCEEDED':
    case 'SUCCEED':
      return 'succeeded';
    case 'RUNNING':
      return 'running';
    case 'WAIT':
    case 'WAITING':
      return 'waiting';
    case 'FAILED':
      return 'failed';
    default:
      return 'unknown';
  }
};

const statusMeta: Record<ChartStatus, { label: string; color: string; icon: React.ReactNode }> = {
  succeeded: { label: '分析完成', color: 'success', icon: <CheckCircleFilled /> },
  running: { label: '分析中', color: 'processing', icon: <SyncOutlined spin /> },
  waiting: { label: '排队中', color: 'default', icon: <ClockCircleFilled /> },
  failed: { label: '分析失败', color: 'error', icon: <ExclamationCircleFilled /> },
  unknown: { label: '状态未知', color: 'default', icon: <ClockCircleFilled /> },
};

/** 后端保存的是 ECharts JSON；损坏或空配置时不让列表页崩溃。 */
const getChartOption = (chart?: API.Chart) => {
  if (!chart?.genChart) return undefined;
  try {
    return JSON.parse(chart.genChart);
  } catch (_error) {
    return undefined;
  }
};

const formatDate = (value?: string) => {
  if (!value) return '时间未记录';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false });
};

const MyChart: React.FC = () => {
  const [charts, setCharts] = useState<API.Chart[]>([]);
  const [total, setTotal] = useState(0);
  const [current, setCurrent] = useState(1);
  const [keyword, setKeyword] = useState('');
  const [searchValue, setSearchValue] = useState('');
  const [statusFilter, setStatusFilter] = useState('all');
  const [loading, setLoading] = useState(true);
  const [selectedChart, setSelectedChart] = useState<API.Chart>();

  const loadCharts = useCallback(async () => {
    setLoading(true);
    try {
      const res = await listMyChartByPageUsingPOST({
        current,
        pageSize: PAGE_SIZE,
        sortField: 'createTime',
        sortOrder: 'desc',
        name: keyword || undefined,
      });
      setCharts(res.data?.records ?? []);
      setTotal(res.data?.total ?? 0);
    } catch (error: any) {
      message.error(error?.response?.data?.message || error?.message || '图表列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [current, keyword]);

  useEffect(() => {
    void loadCharts();
  }, [loadCharts]);

  const visibleCharts = useMemo(
    () =>
      statusFilter === 'all'
        ? charts
        : charts.filter((chart) => normalizeStatus(chart.status) === statusFilter),
    [charts, statusFilter],
  );
  const completedCount = charts.filter(
    (chart) => normalizeStatus(chart.status) === 'succeeded',
  ).length;
  const processingCount = charts.filter((chart) =>
    ['running', 'waiting'].includes(normalizeStatus(chart.status)),
  ).length;
  const failedCount = charts.filter((chart) => normalizeStatus(chart.status) === 'failed').length;

  const removeChart = async (chart: API.Chart) => {
    try {
      const res = await deleteChartUsingPOST({ id: chart.id });
      if (!res.data) {
        message.error(res.message || '删除失败');
        return;
      }
      message.success('图表已删除');
      if (charts.length === 1 && current > 1) setCurrent((page) => page - 1);
      else void loadCharts();
    } catch (error: any) {
      message.error(error?.response?.data?.message || error?.message || '删除失败');
    }
  };

  const renderChart = (chart: API.Chart, compact = true) => {
    const option = getChartOption(chart);
    if (option) {
      return (
        <ReactECharts
          option={option}
          notMerge
          lazyUpdate
          style={{ height: compact ? 220 : 440, width: '100%' }}
        />
      );
    }
    return (
      <div className="chart-preview-empty">
        <BarChartOutlined />
        <span>{chart.genChart ? '图表配置无法显示' : '当前记录没有图表预览'}</span>
      </div>
    );
  };

  return (
    <div className="my-chart-page">
      <section className="charts-hero">
        <div>
          <Tag className="charts-hero__eyebrow" icon={<BarChartOutlined />}>
            EPIPH BI · 分析档案
          </Tag>
          <Title level={2}>我的图表</Title>
          <Text className="charts-hero__description">
            集中查看每次分析的图表、结论与运行状态，让数据洞察随时可回顾。
          </Text>
        </div>
        <Space className="charts-hero__actions" size={10}>
          <Button icon={<ReloadOutlined />} onClick={() => void loadCharts()} loading={loading}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => history.push('/add_chart')}>
            新建分析
          </Button>
        </Space>
      </section>

      <Row gutter={[14, 14]} className="chart-metrics">
        <Col xs={12} lg={6}>
          <Card bordered={false} className="chart-metric-card chart-metric-card--blue">
            <span className="chart-metric-card__label">分析总数</span>
            <strong>{total}</strong>
            <span className="chart-metric-card__note">条分析记录</span>
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card bordered={false} className="chart-metric-card chart-metric-card--green">
            <span className="chart-metric-card__label">本页已完成</span>
            <strong>{completedCount}</strong>
            <span className="chart-metric-card__note">可查看图表与结论</span>
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card bordered={false} className="chart-metric-card chart-metric-card--amber">
            <span className="chart-metric-card__label">本页处理中</span>
            <strong>{processingCount}</strong>
            <span className="chart-metric-card__note">排队或正在分析</span>
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card bordered={false} className="chart-metric-card chart-metric-card--rose">
            <span className="chart-metric-card__label">本页需关注</span>
            <strong>{failedCount}</strong>
            <span className="chart-metric-card__note">失败的分析记录</span>
          </Card>
        </Col>
      </Row>

      <Card className="chart-library" bordered={false}>
        <div className="chart-library__toolbar">
          <div>
            <Title level={4}>分析记录</Title>
            <Text type="secondary">按创建时间排列，点击卡片查看完整结果；状态筛选当前页记录</Text>
          </div>
          <Space className="chart-library__filters" wrap>
            <Input.Search
              allowClear
              value={searchValue}
              onChange={(event) => setSearchValue(event.target.value)}
              onSearch={(value) => {
                setCurrent(1);
                setKeyword(value.trim());
              }}
              placeholder="搜索图表名称"
              enterButton={<SearchOutlined />}
            />
            <Select
              value={statusFilter}
              onChange={setStatusFilter}
              options={[
                { value: 'all', label: '全部状态' },
                { value: 'succeeded', label: '分析完成' },
                { value: 'running', label: '分析中' },
                { value: 'waiting', label: '排队中' },
                { value: 'failed', label: '分析失败' },
              ]}
            />
          </Space>
        </div>

        <Spin spinning={loading}>
          {visibleCharts.length ? (
            <Row gutter={[16, 16]} className="chart-grid">
              {visibleCharts.map((chart) => {
                const status = statusMeta[normalizeStatus(chart.status)];
                return (
                  <Col xs={24} xl={12} key={chart.id}>
                    <Card className="chart-record" bordered={false}>
                      <div className="chart-record__topline">
                        <div className="chart-record__identity">
                          <div className="chart-record__icon">
                            <BarChartOutlined />
                          </div>
                          <div className="chart-record__heading">
                            <Title level={5} ellipsis={{ tooltip: chart.name || '未命名分析' }}>
                              {chart.name || '未命名分析'}
                            </Title>
                            <Text type="secondary">{chart.chartType || '图表类型未记录'}</Text>
                          </div>
                        </div>
                        <Tag color={status.color} icon={status.icon}>
                          {status.label}
                        </Tag>
                      </div>

                      <div className="chart-record__preview">
                        {normalizeStatus(chart.status) === 'succeeded' ? (
                          renderChart(chart)
                        ) : (
                          <div
                            className={`chart-status-panel chart-status-panel--${normalizeStatus(
                              chart.status,
                            )}`}
                          >
                            <span className="chart-status-panel__icon">{status.icon}</span>
                            <strong>{status.label}</strong>
                            <span>
                              {chart.execMessage ||
                                (normalizeStatus(chart.status) === 'failed'
                                  ? '可打开详情查看失败原因'
                                  : '稍后刷新查看最新状态')}
                            </span>
                          </div>
                        )}
                      </div>

                      <div className="chart-record__text">
                        <Text className="chart-record__label">分析目标</Text>
                        <Paragraph ellipsis={{ rows: 2, tooltip: chart.goal }}>
                          {chart.goal || '未填写分析目标'}
                        </Paragraph>
                        {chart.genResult && normalizeStatus(chart.status) === 'succeeded' && (
                          <>
                            <Text className="chart-record__label">结论摘要</Text>
                            <Paragraph ellipsis={{ rows: 2, tooltip: chart.genResult }}>
                              {chart.genResult}
                            </Paragraph>
                          </>
                        )}
                      </div>

                      <div className="chart-record__footer">
                        <Text type="secondary">{formatDate(chart.createTime)}</Text>
                        <Space size={8}>
                          <Button type="link" onClick={() => setSelectedChart(chart)}>
                            查看详情
                          </Button>
                          <Popconfirm
                            title="删除这条分析记录？"
                            description="删除后无法恢复。"
                            okText="删除"
                            cancelText="取消"
                            okButtonProps={{ danger: true }}
                            onConfirm={() => void removeChart(chart)}
                          >
                            <Button
                              type="text"
                              danger
                              icon={<DeleteOutlined />}
                              aria-label="删除图表"
                            />
                          </Popconfirm>
                        </Space>
                      </div>
                    </Card>
                  </Col>
                );
              })}
            </Row>
          ) : (
            <Empty
              className="chart-library__empty"
              description={
                charts.length ? '当前页没有符合条件的记录' : '还没有分析记录，创建一次智能分析吧'
              }
            >
              {!charts.length && (
                <Button type="primary" onClick={() => history.push('/add_chart')}>
                  创建智能分析
                </Button>
              )}
            </Empty>
          )}
        </Spin>

        {total > PAGE_SIZE && (
          <Pagination
            className="chart-library__pagination"
            current={current}
            pageSize={PAGE_SIZE}
            total={total}
            showSizeChanger={false}
            showTotal={(count) => `共 ${count} 条`}
            onChange={setCurrent}
          />
        )}
      </Card>

      <Modal
        open={!!selectedChart}
        onCancel={() => setSelectedChart(undefined)}
        footer={null}
        width={1000}
        centered
        className="chart-detail-modal"
        title={selectedChart?.name || '分析详情'}
      >
        {selectedChart && (
          <div className="chart-detail">
            <div className="chart-detail__meta">
              <Tag color={statusMeta[normalizeStatus(selectedChart.status)].color}>
                {statusMeta[normalizeStatus(selectedChart.status)].label}
              </Tag>
              <Text type="secondary">
                {selectedChart.chartType || '图表类型未记录'} ·{' '}
                {formatDate(selectedChart.createTime)}
              </Text>
            </div>
            <section className="chart-detail__section">
              <Text className="chart-record__label">分析目标</Text>
              <Paragraph>{selectedChart.goal || '未填写分析目标'}</Paragraph>
            </section>
            {selectedChart.genResult && (
              <section className="chart-detail__conclusion">
                <Text className="chart-record__label">分析结论</Text>
                <Paragraph>{selectedChart.genResult}</Paragraph>
              </section>
            )}
            {selectedChart.execMessage && normalizeStatus(selectedChart.status) === 'failed' && (
              <section className="chart-detail__failure">
                <Text strong>失败原因</Text>
                <Paragraph>{selectedChart.execMessage}</Paragraph>
              </section>
            )}
            {normalizeStatus(selectedChart.status) === 'succeeded' && (
              <div className="chart-detail__preview">{renderChart(selectedChart, false)}</div>
            )}
          </div>
        )}
      </Modal>
    </div>
  );
};

export default MyChart;
