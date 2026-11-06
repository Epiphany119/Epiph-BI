import { listMyChartByPageUsingPOST } from '@/services/yubi/chartController';
import {
  ArrowRightOutlined,
  BarChartOutlined,
  CheckCircleFilled,
  ClockCircleFilled,
  ExclamationCircleFilled,
  FileSearchOutlined,
  PlusOutlined,
  ReloadOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';
import { history } from '@umijs/max';
import { Button, Card, Col, Empty, message, Row, Skeleton, Space, Tag, Typography } from 'antd';
import React, { useCallback, useEffect, useState } from 'react';
import './Dashboard.less';

const { Text, Title } = Typography;
const RECENT_PAGE_SIZE = 8;

type NormalizedStatus = 'succeeded' | 'processing' | 'failed' | 'unknown';

/** 服务端状态枚举为大写；先归一化，再用于工作台统计与展示。 */
const normalizeStatus = (status?: string): NormalizedStatus => {
  switch ((status || '').trim().toUpperCase()) {
    case 'SUCCEEDED':
    case 'SUCCEED':
      return 'succeeded';
    case 'WAITING':
    case 'WAIT':
    case 'RUNNING':
      return 'processing';
    case 'FAILED':
      return 'failed';
    default:
      return 'unknown';
  }
};

const statusLabel: Record<NormalizedStatus, string> = {
  succeeded: '分析完成',
  processing: '处理中',
  failed: '分析失败',
  unknown: '状态未知',
};

const statusColor: Record<NormalizedStatus, string> = {
  succeeded: 'success',
  processing: 'processing',
  failed: 'error',
  unknown: 'default',
};

const formatDate = (value?: string) => {
  if (!value) return '时间未记录';
  return value.replace('T', ' ').slice(0, 16);
};

const Dashboard: React.FC = () => {
  const [charts, setCharts] = useState<API.Chart[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);

  const loadCharts = useCallback(async () => {
    setLoading(true);
    try {
      const res = await listMyChartByPageUsingPOST({
        current: 1,
        pageSize: RECENT_PAGE_SIZE,
        sortField: 'createTime',
        sortOrder: 'desc',
      });
      setCharts(res.data?.records ?? []);
      setTotal(res.data?.total ?? res.data?.records?.length ?? 0);
    } catch (error: any) {
      message.error(error?.response?.data?.message || error?.message || '分析记录加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadCharts();
  }, [loadCharts]);

  const completed = charts.filter((item) => normalizeStatus(item.status) === 'succeeded').length;
  const processing = charts.filter((item) => normalizeStatus(item.status) === 'processing').length;
  const failed = charts.filter((item) => normalizeStatus(item.status) === 'failed').length;

  const openCharts = () => history.push('/my_chart');

  return (
    <div className="dashboard-page">
      <section className="dashboard-hero">
        <div className="dashboard-hero__copy">
          <Tag className="dashboard-hero__eyebrow" icon={<BarChartOutlined />}>
            EPIPH BI · 数据洞察工作区
          </Tag>
          <Title level={2}>分析工作台</Title>
          <Text className="dashboard-hero__description">
            从表格开始，快速创建分析任务；完成后随时回到图表库查看结果。
          </Text>
        </div>
        <Space className="dashboard-hero__actions" size={10}>
          <Button size="large" onClick={() => history.push('/add_chart_async')}>
            异步分析
          </Button>
          <Button
            type="primary"
            size="large"
            icon={<PlusOutlined />}
            onClick={() => history.push('/add_chart')}
          >
            新建分析
          </Button>
        </Space>
      </section>

      <Row gutter={[16, 16]} className="dashboard-metrics">
        <Col xs={12} lg={6}>
          <Card className="dashboard-metric dashboard-metric--blue" bordered={false}>
            <span className="dashboard-metric__label">历史分析总数</span>
            <strong>{loading ? '—' : total}</strong>
            <span className="dashboard-metric__note">全部历史记录</span>
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card className="dashboard-metric dashboard-metric--green" bordered={false}>
            <span className="dashboard-metric__label">最近记录已完成</span>
            <strong>{loading ? '—' : completed}</strong>
            <span className="dashboard-metric__note">最近 {RECENT_PAGE_SIZE} 条分析</span>
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card className="dashboard-metric dashboard-metric--amber" bordered={false}>
            <span className="dashboard-metric__label">最近记录处理中</span>
            <strong>{loading ? '—' : processing}</strong>
            <span className="dashboard-metric__note">等待或正在运行</span>
          </Card>
        </Col>
        <Col xs={12} lg={6}>
          <Card className="dashboard-metric dashboard-metric--rose" bordered={false}>
            <span className="dashboard-metric__label">最近记录失败</span>
            <strong>{loading ? '—' : failed}</strong>
            <span className="dashboard-metric__note">可到图表库查看原因</span>
          </Card>
        </Col>
      </Row>

      <Row gutter={[18, 18]} className="dashboard-content">
        <Col xs={24} xl={16}>
          <Card className="dashboard-panel dashboard-recent-panel" bordered={false}>
            <div className="dashboard-section-heading">
              <div>
                <Title level={4}>最近分析</Title>
                <Text type="secondary">按创建时间排列，展示最近 {RECENT_PAGE_SIZE} 条记录</Text>
              </div>
              <Space>
                <Button
                  aria-label="刷新分析记录"
                  icon={<ReloadOutlined />}
                  onClick={loadCharts}
                  loading={loading}
                />
                <Button type="link" onClick={openCharts}>
                  查看全部 <ArrowRightOutlined />
                </Button>
              </Space>
            </div>

            {loading ? (
              <div className="dashboard-loading-list">
                <Skeleton active paragraph={{ rows: 2 }} />
                <Skeleton active paragraph={{ rows: 2 }} />
              </div>
            ) : charts.length === 0 ? (
              <Empty
                className="dashboard-empty"
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="还没有分析记录，创建一项分析开始探索数据吧"
              >
                <Button type="primary" onClick={() => history.push('/add_chart')}>
                  新建分析
                </Button>
              </Empty>
            ) : (
              <div className="dashboard-record-list">
                {charts.slice(0, 5).map((item) => {
                  const status = normalizeStatus(item.status);
                  return (
                    <button
                      type="button"
                      className="dashboard-record"
                      key={item.id}
                      onClick={openCharts}
                    >
                      <span className="dashboard-record__icon">
                        <BarChartOutlined />
                      </span>
                      <span className="dashboard-record__body">
                        <span className="dashboard-record__title-row">
                          <strong>{item.name || '未命名分析'}</strong>
                          <Tag color={statusColor[status]}>
                            {status === 'succeeded' && <CheckCircleFilled />}
                            {status === 'processing' && <ClockCircleFilled />}
                            {status === 'failed' && <ExclamationCircleFilled />}
                            {statusLabel[status]}
                          </Tag>
                        </span>
                        <span className="dashboard-record__goal">
                          {item.goal || '暂无分析目标'}
                        </span>
                        <span className="dashboard-record__meta">
                          <span>{item.chartType || '图表类型未设置'}</span>
                          <span>{formatDate(item.createTime)}</span>
                        </span>
                      </span>
                      <ArrowRightOutlined className="dashboard-record__arrow" />
                    </button>
                  );
                })}
              </div>
            )}
          </Card>
        </Col>

        <Col xs={24} xl={8}>
          <Card className="dashboard-panel dashboard-quick-panel" bordered={false}>
            <div className="dashboard-section-heading dashboard-section-heading--compact">
              <div>
                <Title level={4}>快捷操作</Title>
                <Text type="secondary">选择适合当前需求的分析方式</Text>
              </div>
            </div>
            <Button
              className="dashboard-action-card dashboard-action-card--primary"
              type="primary"
              icon={<ThunderboltOutlined />}
              onClick={() => history.push('/add_chart')}
            >
              <span>
                <strong>即时分析</strong>
                <small>等待当前分析结果</small>
              </span>
              <ArrowRightOutlined />
            </Button>
            <Button
              className="dashboard-action-card"
              icon={<ClockCircleFilled />}
              onClick={() => history.push('/add_chart_async')}
            >
              <span>
                <strong>后台分析</strong>
                <small>提交任务后继续其他操作</small>
              </span>
              <ArrowRightOutlined />
            </Button>
            <Button
              className="dashboard-action-card"
              icon={<FileSearchOutlined />}
              onClick={openCharts}
            >
              <span>
                <strong>我的图表</strong>
                <small>查看历史分析与任务状态</small>
              </span>
              <ArrowRightOutlined />
            </Button>
          </Card>

          <Card className="dashboard-panel dashboard-process-panel" bordered={false}>
            <div className="dashboard-section-heading dashboard-section-heading--compact">
              <div>
                <Title level={4}>一次分析如何完成</Title>
                <Text type="secondary">从文件到可查看的洞察</Text>
              </div>
            </div>
            <div className="dashboard-process-step">
              <span>01</span>
              <div>
                <strong>上传表格</strong>
                <small>选择数据并写下分析目标</small>
              </div>
            </div>
            <div className="dashboard-process-step">
              <span>02</span>
              <div>
                <strong>整理与分析</strong>
                <small>系统处理数据并生成图表内容</small>
              </div>
            </div>
            <div className="dashboard-process-step">
              <span>03</span>
              <div>
                <strong>查看结论</strong>
                <small>在我的图表中回顾分析结果</small>
              </div>
            </div>
          </Card>
        </Col>
      </Row>
    </div>
  );
};

export default Dashboard;
