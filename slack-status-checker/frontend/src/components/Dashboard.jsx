import React, { useState, useEffect } from 'react';
import { Box, Typography, CircularProgress, Card, CardContent, Grid, LinearProgress } from '@mui/material';

const Dashboard = () => {
    const [userName, setUserName] = useState('');
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState(null);
    const [productivity, setProductivity] = useState({
        status: '',
        statusEmoji: '',
        statusText: '',
        huddle: false,
        lastActive: null
    });
    const [efficiencyData, setEfficiencyData] = useState({
        onlineTime: 0,
        huddleTime: 0,
        productivityScore: 0,
        hourlyMetrics: []
    });

    useEffect(() => {
        let ws = null;
        let reconnectTimeout = null;
        let isIntentionalClose = false;
        let statusInterval = null;

        const connectWebSocket = () => {
            try {
                if (ws && ws.readyState === WebSocket.OPEN) {
                    return;
                }

                ws = new WebSocket('ws://localhost:8080/status');

                ws.onopen = () => {
                    console.log('WebSocket Connected');
                    setError(null);
                    // Initial status request with efficiency data
                    ws.send(JSON.stringify({ 
                        type: 'getStatus',
                        includeEfficiency: true
                    }));

                    if (statusInterval) {
                        clearInterval(statusInterval);
                    }
                    statusInterval = setInterval(() => {
                        if (ws && ws.readyState === WebSocket.OPEN) {
                            ws.send(JSON.stringify({ 
                                type: 'getStatus',
                                includeEfficiency: true
                            }));
                        } else {
                            console.log('WebSocket not ready, attempting reconnect...');
                            connectWebSocket();
                        }
                    }, 30000);
                };

                ws.onmessage = (event) => {
                    try {
                        const data = JSON.parse(event.data);
                        console.log('Received message:', data);

                        if (data.type === 'userInfo') {
                            const name = data.realName || data.userName;
                            setUserName(name);
                            setProductivity({
                                status: data.status,
                                statusEmoji: data.statusEmoji,
                                statusText: data.statusText,
                                huddle: data.huddle,
                                lastActive: data.lastActive
                            });

                            // Handle efficiency metrics
                            if (data.efficiency) {
                                const metrics = data.efficiency.hourlyMetrics || [];
                                setEfficiencyData(prev => ({
                                    ...prev,
                                    onlineTime: data.efficiency.onlineTime || prev.onlineTime,
                                    huddleTime: data.efficiency.huddleTime || prev.huddleTime,
                                    productivityScore: data.efficiency.productivityScore || prev.productivityScore,
                                    hourlyMetrics: metrics.map(m => ({
                                        timeBlock: m.timeBlock,
                                        efficiency: m.efficiency || 0
                                    }))
                                }));
                            }
                            setLoading(false);
                        }
                    } catch (error) {
                        console.error('Failed to parse message:', error);
                    }
                };

                ws.onerror = (error) => {
                    console.error('WebSocket error:', error);
                    if (!userName) {
                        setError('Connection error occurred');
                    }
                    // Attempt to reconnect on error
                    setTimeout(connectWebSocket, 3000);
                };

                ws.onclose = (event) => {
                    console.log('WebSocket disconnected:', event.code, event.reason);
                    if (!isIntentionalClose) {
                        if (reconnectTimeout) {
                            clearTimeout(reconnectTimeout);
                        }
                        reconnectTimeout = setTimeout(connectWebSocket, 3000);
                    }
                };

            } catch (error) {
                console.error('Connection error:', error);
                if (!userName) {
                    setError('Failed to establish connection');
                    setLoading(false);
                }
            }
        };

        connectWebSocket();

        return () => {
            isIntentionalClose = true;
            if (statusInterval) clearInterval(statusInterval);
            if (reconnectTimeout) clearTimeout(reconnectTimeout);
            if (ws) ws.close();
        };
    }, []);

    return (
        <Box display="flex" flexDirection="column" alignItems="center" p={4}>
            <Typography variant="h4" gutterBottom>
                Welcome, {userName}!
            </Typography>
            
            <Grid container spacing={3} maxWidth="md">
                <Grid item xs={12} md={6}>
                    <Card elevation={3} sx={{ 
                        background: productivity.status === 'active' ? 'linear-gradient(145deg, #e8f5e9, #c8e6c9)' : 'linear-gradient(145deg, #ffebee, #ffcdd2)',
                        transition: 'all 0.3s ease'
                    }}>
                        <CardContent>
                            <Typography variant="h6" gutterBottom sx={{ 
                                color: productivity.status === 'active' ? '#2e7d32' : '#c62828',
                                fontWeight: 'bold'
                            }}>
                                Current Status
                            </Typography>
                            <Box sx={{ 
                                display: 'flex', 
                                alignItems: 'center', 
                                gap: 2,
                                padding: 2,
                                borderRadius: 2,
                                backgroundColor: 'rgba(255, 255, 255, 0.6)'
                            }}>
                                <Box sx={{ 
                                    width: 12, 
                                    height: 12, 
                                    borderRadius: '50%',
                                    backgroundColor: productivity.status === 'active' ? '#2e7d32' : '#c62828',
                                    boxShadow: '0 0 10px rgba(0,0,0,0.2)',
                                    animation: productivity.status === 'active' ? 'pulse 2s infinite' : 'none',
                                    '@keyframes pulse': {
                                        '0%': { boxShadow: '0 0 0 0 rgba(46, 125, 50, 0.4)' },
                                        '70%': { boxShadow: '0 0 0 10px rgba(46, 125, 50, 0)' },
                                        '100%': { boxShadow: '0 0 0 0 rgba(46, 125, 50, 0)' }
                                    }
                                }} />
                                <Typography variant="h5" sx={{ 
                                    fontWeight: 'bold',
                                    color: productivity.status === 'active' ? '#2e7d32' : '#c62828'
                                }}>
                                    {productivity.status === 'active' ? 'Online' : 'Offline'}
                                </Typography>
                            </Box>
                            {productivity.statusText && (
                                <Typography color="textSecondary" sx={{ mt: 2, fontStyle: 'italic' }}>
                                    "{productivity.statusText}"
                                </Typography>
                            )}
                        </CardContent>
                    </Card>
                </Grid>
                
                <Grid item xs={12} md={6}>
                    <Card elevation={3} sx={{ 
                        background: 'linear-gradient(145deg, #f3e5f5, #e1bee7)',
                        transition: 'all 0.3s ease'
                    }}>
                        <CardContent>
                            <Typography variant="h6" gutterBottom sx={{ color: '#4a148c', fontWeight: 'bold' }}>
                                Huddle Status
                            </Typography>
                            <Box sx={{ 
                                display: 'flex', 
                                alignItems: 'center', 
                                gap: 2,
                                padding: 2,
                                borderRadius: 2,
                                backgroundColor: 'rgba(255, 255, 255, 0.6)'
                            }}>
                                {productivity.huddle ? (
                                    <>
                                        <span style={{ fontSize: '24px', color: '#4a148c' }}>✅</span>
                                        <Typography variant="h5" sx={{ color: '#4a148c', fontWeight: 'bold' }}>
                                            In a Huddle
                                        </Typography>
                                    </>
                                ) : (
                                    <>
                                        <span style={{ fontSize: '24px', color: '#4a148c' }}>❌</span>
                                        <Typography variant="h5" sx={{ color: '#4a148c', fontWeight: 'bold' }}>
                                            Not in Huddle
                                        </Typography>
                                    </>
                                )}
                            </Box>
                            {productivity.lastActive && (
                                <Typography sx={{ 
                                    mt: 2, 
                                    color: '#4a148c',
                                    fontSize: '0.9rem',
                                    fontStyle: 'italic'
                                }}>
                                    Last active: {new Date(productivity.lastActive * 1000).toLocaleString()}
                                </Typography>
                            )}
                        </CardContent>
                    </Card>
                </Grid>
            </Grid>
            <Grid container spacing={3} maxWidth="md" mt={3}>
                <Grid item xs={12}>
                    <Card elevation={3} sx={{ background: 'linear-gradient(145deg, #e3f2fd, #bbdefb)' }}>
                        <CardContent>
                            <Typography variant="h6" gutterBottom sx={{ color: '#1565c0', fontWeight: 'bold' }}>
                                Productivity Metrics
                            </Typography>
                            
                            <Box sx={{ mb: 3 }}>
                                <Typography variant="subtitle1" sx={{ color: '#1565c0' }}>
                                    Overall Productivity Score
                                </Typography>
                                <Box display="flex" alignItems="center" gap={2}>
                                    <LinearProgress 
                                        variant="determinate" 
                                        value={efficiencyData.productivityScore} 
                                        sx={{ 
                                            width: '100%', 
                                            height: 10, 
                                            borderRadius: 5,
                                            backgroundColor: '#e3f2fd',
                                            '& .MuiLinearProgress-bar': {
                                                backgroundColor: '#1565c0'
                                            }
                                        }} 
                                    />
                                    <Typography variant="body1" sx={{ minWidth: 50 }}>
                                        {efficiencyData.productivityScore.toFixed(1)}%
                                    </Typography>
                                </Box>
                            </Box>

                            <Grid container spacing={2}>
                                <Grid item xs={6}>
                                    <Typography variant="subtitle2" color="textSecondary">
                                        Online Time
                                    </Typography>
                                    <Typography variant="h6" sx={{ color: '#2e7d32' }}>
                                        {(efficiencyData.onlineTime / 3600).toFixed(2)} hours
                                    </Typography>
                                </Grid>
                                <Grid item xs={6}>
                                    <Typography variant="subtitle2" color="textSecondary">
                                        Huddle Time
                                    </Typography>
                                    <Typography variant="h6" sx={{ color: '#4a148c' }}>
                                        {(efficiencyData.huddleTime / 3600).toFixed(2)} hours
                                    </Typography>
                                </Grid>
                            </Grid>

                            <Box mt={3}>
                                <Typography variant="subtitle1" gutterBottom sx={{ color: '#1565c0' }}>
                                    Hourly Efficiency
                                </Typography>
                                <Box sx={{ 
                                    display: 'flex', 
                                    flexDirection: 'column', 
                                    gap: 1,
                                    maxHeight: 200,
                                    overflowY: 'auto'
                                }}>
                                    {efficiencyData.hourlyMetrics.map((hour, index) => (
                                        <Box key={index} sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
                                            <Typography variant="body2" sx={{ minWidth: 100 }}>
                                                {hour.timeBlock}
                                            </Typography>
                                            <LinearProgress 
                                                variant="determinate" 
                                                value={hour.efficiency * 100}
                                                sx={{ 
                                                    flexGrow: 1,
                                                    height: 8,
                                                    borderRadius: 4,
                                                    backgroundColor: '#e3f2fd',
                                                    '& .MuiLinearProgress-bar': {
                                                        backgroundColor: '#1565c0'
                                                    }
                                                }}
                                            />
                                            <Typography variant="body2" sx={{ minWidth: 50 }}>
                                                {(hour.efficiency * 100).toFixed(1)}%
                                            </Typography>
                                        </Box>
                                    ))}
                                </Box>
                            </Box>
                        </CardContent>
                    </Card>
                </Grid>
            </Grid>
        </Box>
    );
};

export default Dashboard;